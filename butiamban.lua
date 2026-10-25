--=====================================================================
--  butiamban v1.0.0
--  Couche anti-detection cote client pour Roblox (executor).
--
--  Ce que ca fait, concretement :
--    - bouclier Kick        : bloque les appels Kick venant de scripts locaux
--    - bouclier Teleport    : bloque les teleports vers un autre jeu
--    - garde remotes        : token bucket par remote + plafond global,
--                             uniquement sur les appels venant de l'executor
--                             (le jeu lui-meme n'est jamais bride),
--                             + assainissement des arguments (NaN, inf,
--                             strings geantes, tables enormes)
--    - sentinelle scripts   : detecte et neutralise les LocalScripts / Scripts
--                             RunContext=Client qui font de l'anti-cheat
--                             (scan + surveillance DescendantAdded)
--    - garde mouvement      : clamp de WalkSpeed a une valeur plausible
--    - anti-AFK             : VirtualUser sur Idled
--    - API                 : Fire / StepTeleport / Allow / SetMaxSpeed
--
--  Ce que ca ne fait pas : Byfron/Hyperion. Un script Lua ne desactive pas
--  l'anti-tamper du client, ne change pas le HWID et ne leve pas un ban
--  deja applique. Voir README.md pour la repartition exacte.
--
--  Touches : Insert = HUD   |   End = panique (dechargement total)
--=====================================================================

local AB = {}
AB.Version = "1.0.0"
AB.Name = "butiamban"

--=====================================================================
--  0. SERVICES
--=====================================================================
local Players      = game:GetService("Players")
local RunService   = game:GetService("RunService")
local UIS          = game:GetService("UserInputService")
local TweenService = game:GetService("TweenService")
local GuiService   = game:GetService("GuiService")
local VirtualUser  = game:GetService("VirtualUser")
local ReplicatedFirst = game:GetService("ReplicatedFirst")
local TeleportService = game:GetService("TeleportService")

local LP = Players.LocalPlayer
if not LP then
    local deadline = os.clock() + 5
    repeat
        task.wait(0.1)
        LP = Players.LocalPlayer
    until LP or os.clock() > deadline
end
if not LP then
    warn("[butiamban] LocalPlayer indisponible, relance apres le chargement")
    return
end

--=====================================================================
--  1. SHIM EXECUTEUR
--=====================================================================
local function grabEnv()
    local ok, g = pcall(function()
        if type(getgenv) == "function" then return getgenv() end
        return nil
    end)
    if ok and type(g) == "table" then return g end
    return _G
end

local GENV = grabEnv()

local function ex(name)
    local fn = rawget(GENV, name)
    if type(fn) == "function" then return fn end
    local ok, v = pcall(function() return _G[name] end)
    if ok and type(v) == "function" then return v end
    return nil
end

local CAP = {
    hookmetamethod  = ex("hookmetamethod"),
    hookfunction    = ex("hookfunction"),
    getrawmetatable = ex("getrawmetatable"),
    setreadonly     = ex("setreadonly"),
    getnamecallmethod = ex("getnamecallmethod"),
    checkcaller     = ex("checkcaller"),
    getconnections  = ex("getconnections"),
    writefile       = ex("writefile"),
    readfile        = ex("readfile"),
    isfile          = ex("isfile"),
    makefolder      = ex("makefolder"),
    isfolder        = ex("isfolder"),
    gethui          = ex("gethui"),
    protectgui      = ex("protectgui"),
    setfpscap       = ex("setfpscap"),
}

local EXECUTOR = "unknown"
do
    local identify = ex("identifyexecutor") or ex("getexecutorname")
    if identify then
        local ok, name = pcall(identify)
        if ok and type(name) == "string" and name ~= "" then
            EXECUTOR = name
        end
    end
end

-- re-execution : on decharge l'instance precedente
if type(GENV.butiamban) == "table" and type(GENV.butiamban.Panic) == "function" then
    pcall(GENV.butiamban.Panic)
end

--=====================================================================
--  2. UTILS + STOCKAGE
--=====================================================================
local function clamp(n, lo, hi)
    if n < lo then return lo end
    if n > hi then return hi end
    return n
end

local function round(n, step)
    step = step or 1
    if step <= 0 then return n end
    return math.floor(n / step + 0.5) * step
end

local function serialize(value)
    local t = type(value)
    if t == "string" then
        return string.format("%q", value)
    elseif t == "number" then
        if value ~= value or value == math.huge or value == -math.huge then return "0" end
        return string.format("%.14g", value)
    elseif t == "boolean" then
        return value and "true" or "false"
    elseif t == "table" then
        local out = { "{" }
        for k, v in pairs(value) do
            local kt = type(k)
            if kt == "string" then
                out[#out + 1] = "[" .. string.format("%q", k) .. "]="
            elseif kt == "number" then
                out[#out + 1] = "[" .. string.format("%d", k) .. "]="
            else
                continue
            end
            out[#out + 1] = serialize(v)
            out[#out + 1] = ","
        end
        out[#out + 1] = "}"
        return table.concat(out)
    end
    return "nil"
end

local function deserialize(src)
    if type(src) ~= "string" or src == "" then return nil end
    local compile = loadstring or load
    if type(compile) ~= "function" then return nil end
    local chunk = compile("return " .. src)
    if not chunk then return nil end
    local ok, result = pcall(chunk)
    if ok and type(result) == "table" then return result end
    return nil
end

local FS = {
    dir = "butiamban",
    file = "butiamban/config.lua",
    ready = (CAP.writefile and CAP.readfile) ~= nil,
}

local function writeConfig(data)
    if not FS.ready then return false end
    if CAP.makefolder and CAP.isfolder then
        local ok, exists = pcall(CAP.isfolder, FS.dir)
        if not (ok and exists) then pcall(CAP.makefolder, FS.dir) end
    end
    return (pcall(CAP.writefile, FS.file, "return " .. serialize(data)))
end

local function readConfig()
    if not FS.ready then return nil end
    local ok, exists = pcall(CAP.isfile, FS.file)
    if not (ok and exists) then return nil end
    local readOk, body = pcall(CAP.readfile, FS.file)
    if not readOk or type(body) ~= "string" then return nil end
    return deserialize(body)
end

--=====================================================================
--  3. CONFIG + STATS
--=====================================================================
local Config = {
    kickShield    = true,
    teleportShield = "same",      -- off | same | strict
    remoteGuard   = true,
    sentryMode    = "balanced",   -- observe | balanced | strict
    movementGuard = true,
    maxSpeed      = 40,
    antiAfk       = true,
    hudVisible    = true,
    hudKey        = Enum.KeyCode.Insert,
    panicKey      = Enum.KeyCode.End,
    remoteRate    = 12,           -- jetons par seconde, par remote
    remoteBurst   = 20,
    globalRate    = 240,          -- plafond global executor, par seconde
    allow         = {},
}

local Stats = {
    kicks = 0,
    teleports = 0,
    remotes = 0,
    badArgs = 0,
    scripts = 0,
    reinstalls = 0,
    scans = 0,
}

local Log = {}
local RefreshLogView   -- forward, assigne par le HUD
local RefreshStatView  -- forward

local function hostTime()
    local ok, stamp = pcall(function() return os.date("%H:%M:%S") end)
    return ok and stamp or "--:--:--"
end

local function pushLog(kind, text)
    table.insert(Log, 1, { at = hostTime(), kind = kind, text = tostring(text) })
    while #Log > 40 do
        table.remove(Log)
    end
    if RefreshLogView then RefreshLogView() end
    if RefreshStatView then RefreshStatView() end
end

--=====================================================================
--  4. MOTEUR DE HOOK
--=====================================================================
local namecallHookRef = nil
local oldNamecall = nil
local hooksReady = false
local trustedDepth = 0

local isCaller = CAP.checkcaller
local getMethod = CAP.getnamecallmethod

--=====================================================================
--  5. GARDE REMOTES
--=====================================================================
local pathCache = setmetatable({}, { __mode = "k" })
local buckets = setmetatable({}, { __mode = "k" })

local globalBucket = { tokens = Config.globalRate, last = os.clock() }

local function remotePath(remote)
    local cached = pathCache[remote]
    if cached then return cached end
    local ok, path = pcall(function() return remote:GetFullName() end)
    path = (ok and path) or tostring(remote)
    pathCache[remote] = path
    return path
end

local function refill(bucket, rate, burst, now)
    local elapsed = now - bucket.last
    if elapsed > 0 then
        bucket.last = now
        bucket.tokens = math.min(burst, bucket.tokens + elapsed * rate)
    end
end

local function takeRemoteToken(remote)
    local now = os.clock()
    local bucket = buckets[remote]
    if not bucket then
        bucket = { tokens = Config.remoteBurst, last = now }
        buckets[remote] = bucket
    end
    refill(bucket, Config.remoteRate, Config.remoteBurst, now)
    if bucket.tokens < 1 then return false end
    bucket.tokens = bucket.tokens - 1
    return true
end

local function takeGlobalToken()
    local now = os.clock()
    refill(globalBucket, Config.globalRate, Config.globalRate, now)
    if globalBucket.tokens < 1 then return false end
    globalBucket.tokens = globalBucket.tokens - 1
    return true
end

local function argsSafe(...)
    local count = select("#", ...)
    if count > 12 then return false, "trop d'arguments" end
    for index = 1, count do
        local value = select(index, ...)
        local vt = type(value)
        if vt == "number" then
            if value ~= value then return false, "NaN" end
            if value == math.huge or value == -math.huge then return false, "infini" end
            if math.abs(value) > 1e15 then return false, "nombre hors bornes" end
        elseif vt == "string" then
            if #value > 8192 then return false, "string trop longue" end
        elseif vt == "table" then
            local elements = 0
            for _ in pairs(value) do
                elements = elements + 1
                if elements > 128 then return false, "table trop grande" end
            end
        elseif vt == "function" or vt == "thread" then
            return false, "type non serialisable"
        end
    end
    return true
end

local function guardRemote(remote, ...)
    if not Config.remoteGuard then return true end
    if trustedDepth > 0 then return true end
    if not isCaller then return true end
    if not isCaller() then return true end

    local path = remotePath(remote)
    if Config.allow[path] then return true end

    if not takeRemoteToken(remote) or not takeGlobalToken() then
        Stats.remotes = Stats.remotes + 1
        pushLog("remote", path)
        return false
    end

    local safe, reason = argsSafe(...)
    if not safe then
        Stats.badArgs = Stats.badArgs + 1
        pushLog("args", path .. " · " .. reason)
        return false
    end

    return true
end

--=====================================================================
--  6. BOUCLIERS KICK / TELEPORT
--=====================================================================
local function kickBlocked(self)
    if not Config.kickShield then return false end
    if self ~= LP then return false end
    Stats.kicks = Stats.kicks + 1
    pushLog("kick", "Kick local bloque")
    return true
end

local function teleportBlocked(placeId)
    local mode = Config.teleportShield
    if mode == "off" then return false end
    if not isCaller then return false end
    if isCaller() then return false end

    if mode == "strict" then
        Stats.teleports = Stats.teleports + 1
        pushLog("tp", string.format("teleport bloque · place %s", tostring(placeId)))
        return true
    end

    local destination = tonumber(placeId)
    if destination and destination ~= game.PlaceId then
        Stats.teleports = Stats.teleports + 1
        pushLog("tp", string.format("sortie de place bloquee · %d", destination))
        return true
    end

    return false
end

--=====================================================================
--  7. SENTINELLE SCRIPTS
--=====================================================================
local STRONG_WORDS = {
    "anticheat", "antiexploit", "antihack", "watchdog", "sentinel",
    "warden", "honeypot", "banhammer", "exploiter", "cheatdetect",
    "suspicious", "acdetect", "gameguard",
}

local MEDIUM_WORDS = {
    "security", "guardian", "integrity", "detect", "monitor",
    "protect", "validate", "verify", "audit", "sanction", "punish",
    "blacklist", "enforce", "inspect",
}

local WHITELIST_WORDS = {
    "player", "camera", "control", "chat", "sound", "music", "interface",
    "settings", "notification", "character", "humanoid", "animation",
    "emote", "tutorial", "quest", "dialogue", "localization", "translation",
    "shop", "inventory", "menu", "uigui", "startpack", "input", "replay",
}

local function normalize(text)
    return (string.lower((string.gsub(text or "", "[^%w]", ""))))
end

local function scriptScore(inst)
    local name = inst.Name
    if name == "PlayerModule" or name == "RbxCharacterSounds" then
        return 0, ""
    end

    local norm = normalize(name)
    local score = 0
    local reason = ""

    for _, word in ipairs(STRONG_WORDS) do
        if string.find(norm, word, 1, true) then
            score = score + 3
            reason = word
            break
        end
    end

    if score == 0 then
        for _, word in ipairs(MEDIUM_WORDS) do
            if string.find(norm, word, 1, true) then
                score = score + 1
                reason = word
            end
        end
    end

    if score == 0 then return 0, "" end

    if score < 3 then
        for _, word in ipairs(WHITELIST_WORDS) do
            if string.find(norm, word, 1, true) then
                return 0, ""
            end
        end
    end

    local parent = inst.Parent
    if parent then
        if parent.Name == "PlayerScripts" or parent.Name == "ReplicatedFirst" then
            score = score + 1
        elseif parent:IsDescendantOf(ReplicatedFirst) then
            score = score + 1
        end
    end

    return score, reason
end

local function isClientScript(inst)
    if inst:IsA("LocalScript") then return true end
    if inst:IsA("Script") then
        local ok, context = pcall(function() return inst.RunContext end)
        return ok and context == Enum.RunContext.Client
    end
    return false
end

local function sentryThreshold()
    local mode = Config.sentryMode
    if mode == "strict" then return 2 end
    if mode == "balanced" then return 3 end
    return 999
end

local function neutralize(inst, reason)
    local name = inst.Name
    local ok = pcall(function()
        if inst:IsA("LocalScript") or inst:IsA("Script") then
            pcall(function() inst.Disabled = true end)
        end
        inst:Destroy()
    end)
    if ok then
        Stats.scripts = Stats.scripts + 1
        pushLog("script", name .. " · " .. tostring(reason))
    end
    return ok
end

local function scanRoot(root, budget, threshold)
    local queue = { root }
    local cursor = 1
    local killed = 0
    local visited = 0

    while cursor <= #queue and visited < budget do
        local node = queue[cursor]
        cursor = cursor + 1
        local ok, children = pcall(function() return node:GetChildren() end)
        if ok and type(children) == "table" then
            for _, child in ipairs(children) do
                visited = visited + 1
                if visited >= budget then break end
                if isClientScript(child) then
                    local score, reason = scriptScore(child)
                    if score >= threshold then
                        if neutralize(child, reason) then
                            killed = killed + 1
                        end
                    end
                else
                    queue[#queue + 1] = child
                end
            end
        end
    end

    return killed, visited
end

local function sentryRoots()
    local roots = { ReplicatedFirst }
    if LP then
        local playerScripts = LP:FindFirstChild("PlayerScripts")
        if playerScripts then roots[#roots + 1] = playerScripts end
        local playerGui = LP:FindFirstChild("PlayerGui")
        if playerGui then roots[#roots + 1] = playerGui end
        local character = LP.Character
        if character then roots[#roots + 1] = character end
    end
    return roots
end

local function runSentry()
    local threshold = sentryThreshold()
    if threshold > 100 then return 0 end
    local total = 0
    for _, root in ipairs(sentryRoots()) do
        local killed = scanRoot(root, 4000, threshold)
        total = total + killed
    end
    Stats.scans = Stats.scans + 1
    return total
end

local sentryConnections = {}
local watcherMode = nil

local function installSentryWatcher()
    for _, connection in ipairs(sentryConnections) do
        pcall(function() connection:Disconnect() end)
    end
    sentryConnections = {}

    for _, root in ipairs(sentryRoots()) do
        local connection = root.DescendantAdded:Connect(function(inst)
            if not isClientScript(inst) then return end
            local score, reason = scriptScore(inst)
            if score >= sentryThreshold() then
                task.defer(function()
                    if inst.Parent then neutralize(inst, reason) end
                end)
            end
        end)
        sentryConnections[#sentryConnections + 1] = connection
    end

    watcherMode = Config.sentryMode
end

--=====================================================================
--  8. GARDE MOUVEMENT + ANTI-AFK
--=====================================================================
local function applyMovementGuard()
    if not Config.movementGuard then return end
    local character = LP and LP.Character
    local hum = character and character:FindFirstChildOfClass("Humanoid")
    if not hum then return end
    local maxSpeed = Config.maxSpeed
    if hum.WalkSpeed > maxSpeed then
        hum.WalkSpeed = maxSpeed
    end
end

local function antiAfkPulse()
    if not Config.antiAfk then return end
    pcall(function()
        VirtualUser:CaptureController()
        VirtualUser:ClickButton2(Vector2.new())
    end)
end

--=====================================================================
--  9. HOOK NAMECALL
--=====================================================================
local function callOriginal(self, method, ...)
    if oldNamecall then
        return oldNamecall(self, ...)
    end
    local func = self[method]
    if type(func) == "function" then
        return func(self, ...)
    end
    return nil
end

namecallHookRef = function(self, ...)
    local method = getMethod and getMethod()

    if method then
        if method == "FireServer" or method == "InvokeServer" then
            if not guardRemote(self, ...) then
                return nil
            end
        elseif method == "Kick" then
            if kickBlocked(self) then
                return nil
            end
        elseif method == "Teleport"
            or method == "TeleportAsync"
            or method == "TeleportToPlaceInstance"
            or method == "TeleportToPrivateServer" then
            if teleportBlocked((...)) then
                return nil
            end
        end
    end

    return callOriginal(self, method, ...)
end

local function installNamecall()
    if not getMethod then return false end

    local meta = CAP.getrawmetatable and CAP.getrawmetatable(game)
    local original = meta and rawget(meta, "__namecall")

    if CAP.hookmetamethod then
        local ok, returned = pcall(CAP.hookmetamethod, game, "__namecall", namecallHookRef)
        if ok then
            oldNamecall = returned or original
            hooksReady = true
            return true
        end
    end

    if meta and CAP.setreadonly then
        pcall(CAP.setreadonly, meta, false)
        meta.__namecall = namecallHookRef
        pcall(CAP.setreadonly, meta, true)
        oldNamecall = original
        hooksReady = true
        return true
    end

    return false
end

local function restoreNamecall()
    if not hooksReady then return end
    if not oldNamecall then return end

    if CAP.hookmetamethod then
        pcall(CAP.hookmetamethod, game, "__namecall", oldNamecall)
    end

    local meta = CAP.getrawmetatable and CAP.getrawmetatable(game)
    if meta and rawget(meta, "__namecall") == namecallHookRef then
        pcall(CAP.setreadonly, meta, false)
        rawset(meta, "__namecall", oldNamecall)
        pcall(CAP.setreadonly, meta, true)
    end

    hooksReady = false
end

--=====================================================================
--  10. API PUBLIQUE
--=====================================================================
local function apiFire(remote, ...)
    if type(remote) ~= "userdata" then return nil, "remote invalide" end
    local args = table.pack(...)
    trustedDepth = trustedDepth + 1
    local packed = table.pack(pcall(function()
        return remote:FireServer(table.unpack(args, 1, args.n))
    end))
    trustedDepth = trustedDepth - 1
    if not packed[1] then
        return nil, packed[2]
    end
    return true
end

local function apiInvoke(remote, ...)
    if type(remote) ~= "userdata" then return nil, "remote invalide" end
    local args = table.pack(...)
    trustedDepth = trustedDepth + 1
    local packed = table.pack(pcall(function()
        return remote:InvokeServer(table.unpack(args, 1, args.n))
    end))
    trustedDepth = trustedDepth - 1
    if not packed[1] then
        return nil, packed[2]
    end
    return packed[2]
end

local function apiStepTeleport(position, steps)
    local character = LP and LP.Character
    if not character then return false end
    local root = character:FindFirstChild("HumanoidRootPart") or character.PrimaryPart
    if not root then return false end

    steps = clamp(steps or 8, 2, 64)
    local target = typeof(position) == "Vector3" and position or position.Position
    local start = root.Position

    for index = 1, steps do
        local alpha = index / steps
        local point = start:Lerp(target, alpha)
        local ok = pcall(function()
            character:PivotTo(CFrame.new(point))
        end)
        if not ok then
            root.CFrame = CFrame.new(point)
        end
        if index < steps then
            task.wait(0.045 + math.random() * 0.03)
        end
    end

    return true
end

AB.Config = Config
AB.Stats = Stats
AB.Log = Log
AB.Fire = apiFire
AB.Invoke = apiInvoke
AB.StepTeleport = apiStepTeleport
AB.Panic = function() end   -- remplace plus bas

function AB.SetMaxSpeed(value)
    Config.maxSpeed = clamp(tonumber(value) or 40, 16, 500)
    return Config.maxSpeed
end

function AB.Allow(remote)
    local path = type(remote) == "string" and remote or remotePath(remote)
    Config.allow[path] = true
    return path
end

function AB.Disallow(remote)
    local path = type(remote) == "string" and remote or remotePath(remote)
    Config.allow[path] = nil
    return path
end

function AB.SafeFire(remote, ...)
    return apiFire(remote, ...)
end

--=====================================================================
--  11. HUD
--=====================================================================
local P = {
    bg        = Color3.fromRGB(9, 10, 15),
    panel     = Color3.fromRGB(15, 17, 24),
    card      = Color3.fromRGB(22, 25, 34),
    cardAlt   = Color3.fromRGB(29, 33, 44),
    stroke    = Color3.fromRGB(44, 49, 64),
    text      = Color3.fromRGB(235, 239, 247),
    muted     = Color3.fromRGB(138, 147, 168),
    accent    = Color3.fromRGB(255, 92, 92),
    good      = Color3.fromRGB(157, 255, 94),
    info      = Color3.fromRGB(120, 190, 255),
}

local function new(class, props, parent)
    local inst = Instance.new(class)
    for k, v in pairs(props or {}) do inst[k] = v end
    if parent then inst.Parent = parent end
    return inst
end

local function corner(inst, r)
    return new("UICorner", { CornerRadius = UDim.new(0, r or 8) }, inst)
end

local function stroke(inst, color, thickness)
    return new("UIStroke", {
        Color = color or P.stroke,
        Thickness = thickness or 1,
        ApplyStrokeMode = Enum.ApplyStrokeMode.Border,
    }, inst)
end

local function label(parent, text, size, color, font, textSize)
    return new("TextLabel", {
        Size = size or UDim2.new(1, 0, 1, 0),
        BackgroundTransparency = 1,
        Text = text or "",
        TextColor3 = color or P.text,
        Font = font or Enum.Font.GothamMedium,
        TextSize = textSize or 12,
        TextXAlignment = Enum.TextXAlignment.Left,
        TextTruncate = Enum.TextTruncate.AtEnd,
    }, parent)
end

local function isPress(input)
    return input.UserInputType == Enum.UserInputType.MouseButton1
        or input.UserInputType == Enum.UserInputType.Touch
end

local function mousePos()
    local inset = GuiService:GetGuiInset()
    return UIS:GetMouseLocation() - inset
end

local function makeGui()
    local playerGui = LP:WaitForChild("PlayerGui")
    local parent = playerGui
    if CAP.gethui then
        pcall(function()
            local hui = CAP.gethui()
            if hui then parent = hui end
        end)
    end

    local gui = new("ScreenGui", {
        Name = "\0ab" .. tostring(math.random(100000, 999999)),
        ResetOnSpawn = false,
        IgnoreGuiInset = false,
        ZIndexBehavior = Enum.ZIndexBehavior.Sibling,
        DisplayOrder = 998,
    })
    if CAP.protectgui then pcall(CAP.protectgui, gui) end
    gui.Parent = parent
    if not gui.Parent then gui.Parent = playerGui end
    return gui
end

local Gui = makeGui()

local Root = new("Frame", {
    Size = UDim2.fromOffset(320, 470),
    Position = UDim2.fromOffset(16, 16),
    BackgroundColor3 = P.bg,
    BorderSizePixel = 0,
    ClipsDescendants = true,
    Visible = Config.hudVisible,
}, Gui)
corner(Root, 10)
stroke(Root, P.stroke, 1)

local Header = new("Frame", {
    Size = UDim2.new(1, 0, 0, 36),
    BackgroundColor3 = P.panel,
    BorderSizePixel = 0,
}, Root)

local dot = new("Frame", {
    Size = UDim2.fromOffset(7, 7),
    Position = UDim2.fromOffset(14, 15),
    BackgroundColor3 = P.good,
    BorderSizePixel = 0,
}, Header)
corner(dot, 4)

label(Header, "butiamban", UDim2.new(0, 140, 1, 0), P.text, Enum.Font.GothamBold, 13)
    .Position = UDim2.fromOffset(28, 0)
local versionTag = label(Header, "v" .. AB.Version, UDim2.new(0, 90, 0, 20), P.muted, Enum.Font.GothamMedium, 10)
versionTag.Position = UDim2.new(1, -150, 0.5, -10)
versionTag.TextXAlignment = Enum.TextXAlignment.Right

local hideBtn = new("TextButton", {
    Size = UDim2.fromOffset(22, 22),
    Position = UDim2.new(1, -30, 0.5, -11),
    BackgroundColor3 = P.card,
    Text = "x",
    TextColor3 = P.muted,
    Font = Enum.Font.GothamBold,
    TextSize = 12,
    AutoButtonColor = false,
    BorderSizePixel = 0,
}, Header)
corner(hideBtn, 5)
hideBtn.Activated:Connect(function()
    Root.Visible = false
end)

-- drag
local dragging, dragStart, dragOrigin = false, nil, nil
Header.InputBegan:Connect(function(input)
    if isPress(input) then
        dragging = true
        dragStart = mousePos()
        dragOrigin = Root.Position
    end
end)
UIS.InputChanged:Connect(function(input)
    if not dragging then return end
    if input.UserInputType ~= Enum.UserInputType.MouseMovement
        and input.UserInputType ~= Enum.UserInputType.Touch then return end
    local delta = mousePos() - dragStart
    Root.Position = UDim2.new(
        dragOrigin.X.Scale, dragOrigin.X.Offset + delta.X,
        dragOrigin.Y.Scale, dragOrigin.Y.Offset + delta.Y
    )
end)
UIS.InputEnded:Connect(function(input)
    if isPress(input) then dragging = false end
end)

local Body = new("Frame", {
    Size = UDim2.new(1, 0, 1, -36),
    Position = UDim2.fromOffset(0, 36),
    BackgroundTransparency = 1,
}, Root)
new("UIListLayout", { Padding = UDim.new(0, 5), SortOrder = Enum.SortOrder.LayoutOrder }, Body)
new("UIPadding", {
    PaddingTop = UDim.new(0, 8), PaddingBottom = UDim.new(0, 8),
    PaddingLeft = UDim.new(0, 8), PaddingRight = UDim.new(0, 8),
}, Body)

local layoutCursor = 1
local function nextOrder()
    layoutCursor = layoutCursor + 1
    return layoutCursor
end

-- stats
local StatsRow = new("Frame", {
    Size = UDim2.new(1, 0, 0, 44),
    BackgroundTransparency = 1,
    LayoutOrder = 1,
}, Body)
local statsGrid = new("UIGridLayout", {
    CellSize = UDim2.new(0.25, -4, 1, 0),
    CellPadding = UDim2.fromOffset(4, 0),
    SortOrder = Enum.SortOrder.LayoutOrder,
}, StatsRow)

local StatLabels = {}
local function statCell(index, key, caption)
    local cell = new("Frame", {
        BackgroundColor3 = P.card,
        BorderSizePixel = 0,
        LayoutOrder = index,
    }, StatsRow)
    corner(cell, 6)
    local value = label(cell, "0", UDim2.new(1, 0, 0, 22), P.text, Enum.Font.GothamBold, 14)
    value.TextXAlignment = Enum.TextXAlignment.Center
    value.Position = UDim2.fromOffset(0, 1)
    local name = label(cell, caption, UDim2.new(1, 0, 0, 14), P.muted, Enum.Font.GothamMedium, 9)
    name.TextXAlignment = Enum.TextXAlignment.Center
    name.Position = UDim2.fromOffset(0, 24)
    StatLabels[key] = value
    return cell
end

statCell(1, "kicks", "kicks")
statCell(2, "teleports", "tp")
statCell(3, "remotes", "remotes")
statCell(4, "scripts", "scripts")

RefreshStatView = function()
    if not StatLabels.kicks then return end
    StatLabels.kicks.Text = tostring(Stats.kicks)
    StatLabels.teleports.Text = tostring(Stats.teleports)
    StatLabels.remotes.Text = tostring(Stats.remotes + Stats.badArgs)
    StatLabels.scripts.Text = tostring(Stats.scripts)
end

-- toggles
local function toggleRow(text, key, default, onChange)
    local row = new("Frame", {
        Size = UDim2.new(1, 0, 0, 26),
        BackgroundTransparency = 1,
        LayoutOrder = nextOrder(),
    }, Body)
    label(row, text, UDim2.new(1, -50, 1, 0), P.text, Enum.Font.GothamMedium, 11)

    local sw = new("Frame", {
        Size = UDim2.fromOffset(32, 16),
        Position = UDim2.new(1, -34, 0.5, -8),
        BackgroundColor3 = P.stroke,
        BorderSizePixel = 0,
    }, row)
    corner(sw, 8)
    local knob = new("Frame", {
        Size = UDim2.fromOffset(12, 12),
        Position = UDim2.new(0, 2, 0.5, -6),
        BackgroundColor3 = P.text,
        BorderSizePixel = 0,
    }, sw)
    corner(knob, 6)

    local state = default and true or false
    local function render()
        sw.BackgroundColor3 = state and Color3.fromRGB(60, 120, 40) or P.stroke
        knob.BackgroundColor3 = state and P.good or P.text
        TweenService:Create(knob, TweenInfo.new(0.1), {
            Position = state and UDim2.new(1, -14, 0.5, -6) or UDim2.new(0, 2, 0.5, -6),
        }):Play()
    end

    local hit = new("TextButton", {
        Size = UDim2.new(1, 0, 1, 0),
        BackgroundTransparency = 1,
        Text = "",
        AutoButtonColor = false,
    }, row)
    hit.Activated:Connect(function()
        state = not state
        Config[key] = state
        render()
        if onChange then onChange(state) end
    end)

    render()
    return {
        set = function(value)
            state = value and true or false
            Config[key] = state
            render()
            if onChange then onChange(state) end
        end,
        get = function() return state end,
    }
end

local function cycleRow(text, values, key, labels)
    local row = new("Frame", {
        Size = UDim2.new(1, 0, 0, 26),
        BackgroundTransparency = 1,
        LayoutOrder = nextOrder(),
    }, Body)
    label(row, text, UDim2.new(1, -96, 1, 0), P.text, Enum.Font.GothamMedium, 11)

    local btn = new("TextButton", {
        Size = UDim2.new(0, 88, 0, 20),
        Position = UDim2.new(1, -88, 0.5, -10),
        BackgroundColor3 = P.card,
        Text = "",
        TextColor3 = P.text,
        Font = Enum.Font.GothamMedium,
        TextSize = 10,
        AutoButtonColor = false,
        BorderSizePixel = 0,
    }, row)
    corner(btn, 5)
    stroke(btn, P.stroke, 1)

    local function indexOf(value)
        for index, entry in ipairs(values) do
            if entry == value then return index end
        end
        return 1
    end

    local current = indexOf(Config[key])
    local function render()
        local value = values[current]
        Config[key] = value
        btn.Text = (labels and labels[value]) or value
    end

    btn.Activated:Connect(function()
        current = current + 1
        if current > #values then current = 1 end
        render()
    end)

    render()
    return {
        button = btn,
        set = function(value)
            local index = indexOf(value)
            if index then current = index end
            render()
        end,
        get = function() return values[current] end,
    }
end

local function sliderRow(text, key, min, max, step, suffix)
    local holder = new("Frame", {
        Size = UDim2.new(1, 0, 0, 38),
        BackgroundTransparency = 1,
        LayoutOrder = nextOrder(),
    }, Body)

    label(holder, text, UDim2.new(1, -70, 0, 16), P.text, Enum.Font.GothamMedium, 11)
    local valueLabel = label(holder, "", UDim2.new(0, 70, 0, 16), P.good, Enum.Font.GothamMedium, 10)
    valueLabel.TextXAlignment = Enum.TextXAlignment.Right
    valueLabel.Position = UDim2.new(1, -70, 0, 0)

    local track = new("Frame", {
        Size = UDim2.new(1, 0, 0, 3),
        Position = UDim2.new(0, 0, 0, 24),
        BackgroundColor3 = P.stroke,
        BorderSizePixel = 0,
    }, holder)
    corner(track, 2)

    local fill = new("Frame", {
        Size = UDim2.new(0, 0, 1, 0),
        BackgroundColor3 = P.good,
        BorderSizePixel = 0,
    }, track)
    corner(fill, 2)

    local knob = new("Frame", {
        Size = UDim2.fromOffset(10, 10),
        AnchorPoint = Vector2.new(0.5, 0.5),
        Position = UDim2.new(0, 0, 0.5, 0),
        BackgroundColor3 = P.text,
        BorderSizePixel = 0,
    }, track)
    corner(knob, 5)

    local hit = new("TextButton", {
        Size = UDim2.new(1, 0, 0, 20),
        Position = UDim2.new(0, 0, 0, 14),
        BackgroundTransparency = 1,
        Text = "",
        AutoButtonColor = false,
    }, holder)

    local draggingSlider = false
    local function push(value)
        value = clamp(round(value, step), min, max)
        Config[key] = value
        local alpha = (max == min) and 0 or (value - min) / (max - min)
        fill.Size = UDim2.new(alpha, 0, 1, 0)
        knob.Position = UDim2.new(alpha, 0, 0.5, 0)
        valueLabel.Text = tostring(value) .. (suffix or "")
    end

    local function fromMouse()
        local width = track.AbsoluteSize.X
        if width <= 0 then return end
        local alpha = clamp((mousePos().X - track.AbsolutePosition.X) / width, 0, 1)
        push(min + alpha * (max - min))
    end

    hit.InputBegan:Connect(function(input)
        if isPress(input) then
            draggingSlider = true
            fromMouse()
        end
    end)
    UIS.InputChanged:Connect(function(input)
        if not draggingSlider then return end
        if input.UserInputType == Enum.UserInputType.MouseMovement
            or input.UserInputType == Enum.UserInputType.Touch then
            fromMouse()
        end
    end)
    UIS.InputEnded:Connect(function(input)
        if isPress(input) then draggingSlider = false end
    end)

    push(Config[key] or min)
    return { set = push, get = function() return Config[key] end }
end

local kickToggle = toggleRow("Bouclier Kick", "kickShield", Config.kickShield)
local tpCycle = cycleRow("Bouclier Teleport", { "off", "same", "strict" }, "teleportShield", {
    off = "off", same = "meme jeu", strict = "strict",
})
local remoteToggle = toggleRow("Garde remotes", "remoteGuard", Config.remoteGuard)
local sentryCycle = cycleRow("Sentinelle scripts", { "observe", "balanced", "strict" }, "sentryMode", {
    observe = "observe", balanced = "equilibre", strict = "strict",
})
local moveToggle = toggleRow("Garde mouvement", "movementGuard", Config.movementGuard)
local afkToggle = toggleRow("Anti-AFK", "antiAfk", Config.antiAfk)
local speedSlider = sliderRow("Vitesse max", "maxSpeed", 16, 120, 2, " ws")

-- actions
local Actions = new("Frame", {
    Size = UDim2.new(1, 0, 0, 26),
    BackgroundTransparency = 1,
    LayoutOrder = nextOrder(),
}, Body)

local function actionButton(text, color, order, onClick)
    local btn = new("TextButton", {
        Size = UDim2.new(0, 96, 1, 0),
        Position = UDim2.fromOffset(order * 100, 0),
        BackgroundColor3 = P.card,
        Text = text,
        TextColor3 = color or P.text,
        Font = Enum.Font.GothamMedium,
        TextSize = 10,
        AutoButtonColor = false,
        BorderSizePixel = 0,
    }, Actions)
    corner(btn, 5)
    stroke(btn, P.stroke, 1)
    btn.MouseEnter:Connect(function() btn.BackgroundColor3 = P.cardAlt end)
    btn.MouseLeave:Connect(function() btn.BackgroundColor3 = P.card end)
    btn.Activated:Connect(onClick)
    return btn
end

actionButton("Scanner", P.text, 0, function()
    local killed = runSentry()
    pushLog("scan", string.format("%d script(s) neutralise(s)", killed))
end)
actionButton("Panique", P.accent, 1, function()
    task.defer(function() AB.Panic() end)
end)

-- log
local LogBox = new("ScrollingFrame", {
    Size = UDim2.new(1, 0, 0, 110),
    BackgroundColor3 = P.card,
    BackgroundTransparency = 0.3,
    BorderSizePixel = 0,
    ScrollBarThickness = 2,
    ScrollBarImageColor3 = P.stroke,
    CanvasSize = UDim2.new(0, 0, 0, 0),
    AutomaticCanvasSize = Enum.AutomaticSize.Y,
    ScrollingDirection = Enum.ScrollingDirection.Y,
    LayoutOrder = nextOrder(),
}, Body)
corner(LogBox, 6)
new("UIPadding", { PaddingTop = UDim.new(0, 4), PaddingBottom = UDim.new(0, 4),
    PaddingLeft = UDim.new(0, 6), PaddingRight = UDim.new(0, 6) }, LogBox)

local LogText = label(LogBox, "", UDim2.new(1, -8, 0, 0), P.muted, Enum.Font.Code, 10)
LogText.TextWrapped = true
LogText.TextTruncate = Enum.TextTruncate.None
LogText.TextYAlignment = Enum.TextYAlignment.Top
LogText.AutomaticSize = Enum.AutomaticSize.Y
LogText.Size = UDim2.new(1, -8, 0, 0)

RefreshLogView = function()
    local lines = {}
    local limit = math.min(#Log, 10)
    for index = 1, limit do
        local entry = Log[index]
        lines[#lines + 1] = string.format("[%s] %s: %s", entry.at, entry.kind, entry.text)
    end
    LogText.Text = #lines > 0 and table.concat(lines, "\n") or "aucun evenement"
end

RefreshLogView()
RefreshStatView()

--=====================================================================
--  12. BOUCLES
--=====================================================================
local connections = {}

local function track(connection)
    connections[#connections + 1] = connection
    return connection
end

track(UIS.InputBegan:Connect(function(input, processed)
    if input.KeyCode == Config.hudKey then
        Root.Visible = not Root.Visible
        Config.hudVisible = Root.Visible
    elseif input.KeyCode == Config.panicKey and not processed then
        task.defer(function() AB.Panic() end)
    end
end))

if LP then
    track(LP.Idled:Connect(function()
        antiAfkPulse()
    end))
end

if LP then
    track(LP.CharacterAdded:Connect(function()
        task.wait(0.5)
        if Config.movementGuard then applyMovementGuard() end
        if Config.sentryMode ~= "observe" then
            task.defer(function() runSentry(); installSentryWatcher() end)
        end
    end))
end

task.spawn(function()
    while true do
        task.wait(0.35)
        if AB.Unloaded then break end
        pcall(applyMovementGuard)
    end
end)

task.spawn(function()
    while true do
        task.wait(3)
        if AB.Unloaded then break end
        if Config.sentryMode ~= "observe" then
            pcall(runSentry)
            if watcherMode ~= Config.sentryMode then
                pcall(installSentryWatcher)
            end
        end
        if not hooksReady then
            if installNamecall() then
                Stats.reinstalls = Stats.reinstalls + 1
                pushLog("hook", "namecall reinstalle")
            end
        end
        pcall(RefreshStatView)
    end
end)

task.spawn(function()
    while true do
        task.wait(5)
        if AB.Unloaded then break end
        if Config.antiAfk then pcall(antiAfkPulse) end
    end
end)

--=====================================================================
--  13. PANIQUE / INIT
--=====================================================================
function AB.Panic()
    if AB.Unloaded then return end
    AB.Unloaded = true

    for _, connection in ipairs(connections) do
        pcall(function() connection:Disconnect() end)
    end
    connections = {}

    for _, connection in ipairs(sentryConnections) do
        pcall(function() connection:Disconnect() end)
    end
    sentryConnections = {}

    pcall(restoreNamecall)
    pcall(function() Gui:Destroy() end)
    pcall(writeConfig, {
        version = AB.Version,
        config = Config,
    })

    if GENV then GENV.butiamban = nil end
    print("[butiamban] unloaded")
end

do
    local stored = readConfig()
    if stored and type(stored.config) == "table" then
        for key, value in pairs(stored.config) do
            if Config[key] ~= nil and type(value) == type(Config[key]) then
                Config[key] = value
            end
        end
        -- les touches ne sont pas serialisables en Enum : on les remet
        Config.hudKey = Enum.KeyCode.Insert
        Config.panicKey = Enum.KeyCode.End
    end
end

Root.Visible = Config.hudVisible
kickToggle.set(Config.kickShield)
remoteToggle.set(Config.remoteGuard)
moveToggle.set(Config.movementGuard)
afkToggle.set(Config.antiAfk)
tpCycle.set(Config.teleportShield)
sentryCycle.set(Config.sentryMode)
speedSlider.set(Config.maxSpeed)

if installNamecall() then
    pushLog("init", "namecall hook installe · " .. EXECUTOR)
else
    pushLog("init", "hook indisponible · executor limite")
end

if Config.sentryMode ~= "observe" then
    task.defer(function()
        local killed = runSentry()
        installSentryWatcher()
        if killed > 0 then
            pushLog("scan", string.format("%d script(s) au demarrage", killed))
        end
    end)
end

if GENV then GENV.butiamban = AB end

print(string.format("[butiamban] v%s · %s · hooks:%s · sentinelle:%s",
    AB.Version, EXECUTOR, hooksReady and "oui" or "non", Config.sentryMode))

pushLog("init", "pret · Insert = HUD · End = panique")
