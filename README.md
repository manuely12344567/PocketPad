# PocketPad — Manette Android vers PC Windows (USB & Bluetooth)

**PocketPad** transforme ton téléphone Android en **véritable manette de jeu PC** (manette **Xbox 360 virtuelle** + **Clavier/Souris**) avec **connexion câble USB ultra-rapide (< 2 ms)**, **Bluetooth sans fil** et un **éditeur visuel complet** sur le téléphone pour concevoir tes propres manettes.

## Fonctionnalités clés

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
