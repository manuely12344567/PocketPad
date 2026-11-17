# PocketPad v1.2.0 — Manette Multijoueur (1–4J) & Mode Écran Switch vers PC Windows

**PocketPad** transforme de **1 à 4 téléphones Android simultanément** en **véritables manettes de jeu PC avec écran en direct façon Nintendo Switch** (jusqu’à **4 manettes Xbox 360 virtuelles indépendantes** + **Clavier/Souris** + **diffusion Split-Screen J1–J4**) en combinant librement **câble USB (< 2 ms)**, **Bluetooth sans fil** et **Wi-Fi**, avec un **éditeur visuel complet** sur chaque téléphone.

## Fonctionnalités clés

- **Mode Écran Switch & Split-Screen en direct (v1.2.0)** :
  - **Capture GPU DXGI Desktop Duplication** (repli GDI automatique) sur un canal vidéo TCP dédié (`27185`) séparé des entrées manette (`27183`).
  - **Subdivision Split-Screen 1-clic & sur mesure** : Plein écran partagé, 2J Haut/Bas, 2J Gauche/Droite, 3J (1+2), 4J Grille 2×2, cadres déplaçables/redimensionnables sur aperçu PC, **Vue Menu 1-clic** et **Auto-détection Menu ↔ Match**.
  - **Touches semi-transparentes sur Android** : curseur d’opacité (`15 %`–`100 %`), bouton rapide `Écran: ON/OFF`, mode Ajuster/Remplir, et éditeur visuel avec aperçu du jeu en arrière-plan.

- **Multijoueur jusqu’à 4 joueurs simultanés (J1, J2, J3, J4)** :
  - **Mix total des connexions** : 2 téléphones en câble USB + 1 en Bluetooth + 1 en Wi-Fi, ou tous sur la même méthode — tout fonctionne ensemble !
  - **Branchement dynamique XInput** : Windows voit exactement le nombre de joueurs connectés (1, 2, 3 ou 4 manettes Xbox 360 distinctes).
  - **Retour vibrant indépendant** : chaque joueur ressent uniquement les vibrations de sa propre manette.
  - **Gestion en direct** : badge coloré (`J1` Cyan, `J2` Violet, `J3` Vert, `J4` Orange), permutation de slots (`J1 ⇄ J2`), reconnexion intelligente sur le même slot, et option *« Clavier/Souris réservé à J1 »*.

- **Câble USB prioritaire (< 2 ms)** :
  - **Mode ADB direct (automatique)** : `PocketPad.exe` embarque `adb.exe` et ouvre automatiquement le tunnel USB dès que tu branches le câble.
  - **Mode Partage de connexion USB** : fonctionne aussi sans activer les options développeur Android.
- **Bluetooth sans fil** : serveur RFCOMM dédié intégré dans Windows (aucun port COM manuel à régler) + fallback Wi-Fi / LAN.
- **Manette Xbox 360 virtuelle + Clavier/Souris** :
  - Reconnue nativement par les jeux PC (FIFA/EA FC, GTA V, Forza, Rocket League, émulateurs…) grâce au pilote gratuit **ViGEmBus** (installation guidée en 1 clic directement dans `PocketPad.exe`).
  - Mode **Clavier + Souris** intégré via Win32 `SendInput` (scancodes matériels DirectInput) — fonctionne immédiatement sans installer aucun pilote !
- **Éditeur visuel de manette dans l’application Android** :
  - Glisser-déposer libre + grille magnétique + redimensionnement tactile.
  - Personnalisation du nom, de la forme, de la couleur, de l’opacité, de l’action (Xbox / Clavier / Souris / Joystick WASD ou ZQSD), du mode Maintien (Toggle) et du mode Turbo.
  - **5 profils prêts à jouer** : *Xbox 360 Classique*, *PlayStation Style*, *Course · Volant Gyroscope*, *FPS · Clavier + Souris*, *PC Souris & Clavier*.
  - Retour vibrant (haptique + rumble réel des jeux PC) & import/export JSON des profils.

## Démarrage rapide

1. **Sur Windows :** lance `PocketPad-Setup-1.0.0.exe` (ou `PocketPad-Portable-1.0.0.exe`). Si tu joues à des jeux manette Xbox, clique sur **« Installer le pilote Xbox 360 (ViGEmBus · 1 clic) »**.
2. **Sur Android :** installe `PocketPad-Android-1.0.0.apk`.
3. **Branche ton câble USB** (avec *Débogage USB* ou *Partage de connexion USB* activé) → clique sur **« Connexion Câble USB »** → **« JOUER »** !
4. **Ou en Bluetooth :** associe ton téléphone au PC dans Windows Bluetooth → ouvre PocketPad sur PC → clique sur **« Bluetooth »** sur Android et choisis ton PC.

## Structure du dépôt

- `protocol/` — Spécification du protocole binaire haute vitesse (16 octets par trame manette).
- `windows/` — Application desktop C# / .NET 8 WPF (`PocketPad.Core`, `PocketPad.Windows`, `PocketPad.Tests`, script Inno Setup).
- `android/` — Application Android native Kotlin (`minSdk 26`, `targetSdk 35`, multi-touch Canvas + Éditeur visuel + USB/Bluetooth/Gyroscope/Haptique).
- `.github/workflows/build-release.yml` — Compilation automatisée en parallèle de l’APK Android et des `.exe` Windows.
