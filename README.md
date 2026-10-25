# butiamban

Couche anti-detection côté client pour Roblox, en un seul fichier Lua. Pensée pour tourner à côté de [butiampanel](https://github.com/marketbuss/butiampanel) ou de n'importe quel autre script, sur un executor classique (Delta, Xeno, Solara, Wave, Swift, AWP...).

```
loadstring(game:HttpGet("https://raw.githubusercontent.com/marketbuss/butiamban/main/butiamban.lua"))()
```

Un seul fichier, aucune dépendance, aucun asset distant. Le HUD s'ouvre en haut à gauche.

---

## Touches

| Touche | Action |
|---|---|
| `Insert` | afficher / masquer le HUD |
| `End` | panique : restaure le hook, détruit le HUD, sauvegarde la config, retire `getgenv().butiamban` |

Tout est aussi cliquable dans le HUD.

---

## Ce que fait le script

### 1. Bouclier Kick

Hook `__namecall` sur `game`. Tout appel `Player:Kick()` déclenché depuis un script **local** (anti-cheat client, script de sanction, menu de report) est avalé : le kick ne part pas, un compteur s'incrémente, une ligne apparaît dans le log.

Un kick émis par le **serveur** ne passe pas par le `__namecall` du client : c'est une déconnexion réseau. Aucun script Lua ne peut l'intercepter. Le bouclier couvre la couche client, qui est celle où les anti-cheats maison sanctionnent.

### 2. Bouclier Téléport

Trois modes, cyclables dans le HUD :

| Mode | Comportement |
|---|---|
| `off` | aucun filtrage |
| `meme jeu` (défaut) | laisse passer les téléports vers le `PlaceId` courant, bloque les sorties vers un autre jeu (place de ban, place de shadow-ban) |
| `strict` | bloque tout téléport non initié par l'executor |

### 3. Garde remotes

Token bucket par remote **et** plafond global, appliqués uniquement aux appels qui viennent de l'executor (`checkcaller()` vrai). Le jeu lui-même n'est jamais bridé.

- défaut : 12 appels/s par remote, burst 20, plafond global 240/s
- arguments assainis avant envoi : plus de 12 arguments, `NaN`, `±inf`, nombres au-delà de `1e15`, strings de plus de 8 Ko, tables de plus de 128 entrées → l'appel est refusé et loggé
- allowlist par nom complet (`Workspace.Remotes.Farm:FireServer`) pour les remotes qui doivent tourner sans limite

C'est la protection la plus utile en pratique : la majorité des kicks « exploit detected » côté serveur viennent d'un débit de remotes anormal, pas d'une signature.

### 4. Sentinelle scripts

Scan de `ReplicatedFirst`, `PlayerScripts`, `PlayerGui` et du personnage, à la recherche de scripts clients qui font de l'anti-cheat. Prise en charge des `LocalScript` **et** des `Script` avec `RunContext = Client` (le standard depuis 2023).

Score par nom normalisé :

- mots forts (+3) : `anticheat`, `antiexploit`, `watchdog`, `sentinel`, `warden`, `honeypot`, `banhammer`, `exploiter`, `gameguard`...
- mots moyens (+1) : `security`, `guardian`, `integrity`, `detect`, `monitor`, `validate`, `verify`, `audit`, `sanction`, `punish`...
- contexte (+1) : parent direct `PlayerScripts` / `ReplicatedFirst`
- liste blanche : `player`, `camera`, `control`, `chat`, `sound`, `character`, `humanoid`, `menu`, `settings`... ignorés s'il n'y a pas de mot fort

Trois modes :

| Mode | Seuil de suppression |
|---|---|
| `observe` | aucun kill, comptage seulement |
| `equilibre` (défaut) | score ≥ 3 |
| `strict` | score ≥ 2 |

Action : `Disabled = true` puis `Destroy()`. Un watcher `DescendantAdded` re-tue les scripts que le jeu recharge, et un rescan complet tourne toutes les 3 secondes.

### 5. Garde mouvement

Clamp `Humanoid.WalkSpeed` à la valeur du slider (défaut 40, plage 16–120) toutes les 350 ms. But : ne jamais laisser une vitesse impossible exposée au serveur. Si tu utilises le fly ou un speed hack du panel, monte d'abord le curseur — sinon le garde te ramène à 40.

### 6. Anti-AFK

`VirtualUser:CaptureController()` + clic à chaque `Idled`, plus un pulse toutes les 5 secondes.

### 7. API pour les autres scripts

```lua
local ab = getgenv().butiamban

ab.Fire(remote, ...)          -- FireServer avec bypass du limiteur, args vérifiés
ab.Invoke(remote, ...)        -- InvokeServer idem, retourne le résultat
ab.StepTeleport(position, 12) -- téléport interpolé en N pas (45–75 ms), pas de saut CFrame brut
ab.SetMaxSpeed(120)           -- ajuste le plafond du garde mouvement
ab.Allow(remote)              -- ajoute un remote à l'allowlist (instance ou nom complet)
ab.Disallow(remote)           -- retire de l'allowlist
ab.Stats                      -- table live : kicks, teleports, remotes, badArgs, scripts
ab.Panic()                    -- déchargement complet
```

`StepTeleport` accepte un `Vector3` ou un objet avec `.Position`.

---

## Config

Sauvegarde automatique dans `butiamban/config.lua` (dossier de l'executor, workspace) à chaque panique et au rechargement du script. Contient les six modules, les modes, le plafond de vitesse et l'allowlist. Les touches ne sont pas persistées (les `Enum` ne se sérialisent pas) : `Insert` et `End` sont réappliquées au chargement.

Supprimer le fichier remet tout aux valeurs par défaut.

---

## Ce que le script ne fait pas

| Vecteur | Réalité |
|---|---|
| Byfron / Hyperion | anti-tamper natif du client. Un script Lua injecté après coup ne le désactive pas. Il faut un executor à jour avec son propre contournement. |
| Ban HWID | décidé côté serveur sur une empreinte matérielle. Aucune API Roblox côté client ne la modifie. |
| Ban de compte | idem : la décision est serveur. |
| Signalements joueurs | envoyés par un autre client vers le web Roblox. Invisibles et non interceptables depuis le tien. |
| Détection serveur de valeurs absurdes | partiellement couverte : le garde mouvement borne la vitesse, le garde remotes borne le débit, `StepTeleport` évite les sauts de position. Un serveur qui compare deux snapshots temporels verra quand même un déplacement non physique si tu traverses la map. |
| Kick serveur | voir plus haut : déconnexion réseau, hors de portée d'un hook Lua. |

Le script réduit la **surface côté client** : scripts d'anti-cheat neutralisés, kicks locaux bloqués, débit de remotes humain, arguments propres, téléports hors-jeu filtrés. C'est la partie qui dépend de toi. Le reste dépend de l'executor et du serveur.

---

## Compatibilité

| Fonction | Requis | Fallback |
|---|---|---|
| hook namecall | `hookmetamethod` | `getrawmetatable` + `setreadonly` |
| distinction executor / jeu | `checkcaller` | garde remotes et bouclier téléport désactivés (jamais de bridage du jeu) |
| lecture de la méthode | `getnamecallmethod` | hook non installé, le reste fonctionne |
| HUD protégé | `protectgui` / `gethui` | `PlayerGui` |

Sans `getnamecallmethod`, le HUD s'affiche quand même et la sentinelle, le garde mouvement et l'anti-AFK tournent — seuls les trois hooks sont inactifs, avec la ligne `hook indisponible` dans le log.

---

## Intégration avec butiampanel

Le panel charge ses remotes directement. Pour que son farm passe par le limiteur avec un plafond plus haut :

```lua
local ab = getgenv().butiamban
if ab then
    ab.Allow("ReplicatedStorage.Remotes.Farm")
    ab.SetMaxSpeed(120)
end
```

Ou, pour chaque tir :

```lua
if ab then ab.Fire(remote, args) else remote:FireServer(args) end
```

Sans intégration, le garde remotes s'applique quand même aux appels du panel : 12/s par remote, ce qui couvre un farm à 0,1 s d'intervalle.

---

## Fichiers

```
butiamban.lua   script complet, ~1400 lignes
README.md       ce document
```
