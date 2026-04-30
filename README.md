# Stream SBS

Stream SBS est une application Android composee de deux modules qui permet de diffuser l'ecran d'un smartphone vers un autre smartphone sur le meme reseau local, avec un rendu Side-by-Side (SBS) sur l'appareil recepteur.

Le projet fournit deux applications :

- `sender` : capture l'ecran du smartphone emetteur, encode le flux video et l'envoie sur le reseau local.
- `receiver` : detecte l'emetteur, recoit le flux video et l'affiche en mode SBS ou en affichage simple.

## Fonctionnalites

- Streaming d'ecran Android vers Android en reseau local.
- Rendu Side-by-Side pour afficher deux vues synchronisees du meme flux.
- Mode d'affichage simple pour utiliser le recepteur comme ecran classique.
- Detection automatique du recepteur par broadcast UDP.
- Encodage video H.264 via `MediaCodec`.
- Transport video UDP avec paquets RTP et fragmentation FU-A.
- Decodeur H.264 cote recepteur via `MediaCodec`.
- Profils video configurables :
  - resolutions de 854x480 a 2220x1080 ;
  - 20, 24 ou 30 fps ;
  - debit de 3 a 10 Mbps.
- Reglages de rendu synchronises entre les deux appareils :
  - activation du SBS ;
  - zoom horizontal et vertical ;
  - decalage horizontal et vertical ;
  - opacite d'une superposition camera optionnelle ;
  - profil video.
- Menu de reglages directement disponible sur le recepteur avec les boutons de volume.
- Indicateurs de latence, FPS rendu et profil video courant.
- Service de streaming au premier plan cote emetteur.

## Architecture

Le depot est organise en trois modules Gradle :

```text
common/    Code partage : protocoles, ports, profils video, RTP, helpers H.264
sender/    Application emettrice : capture d'ecran, encodage H.264, envoi UDP
receiver/  Application receptrice : decodage H.264, rendu OpenGL, controles locaux
```

### Flux reseau

Les deux smartphones doivent etre connectes au meme reseau local.

Ports utilises :

| Port | Usage |
| --- | --- |
| `5500/UDP` | Flux video RTP/H.264 |
| `5501/UDP` | Decouverte du recepteur |
| `5502/UDP` | Envoi des reglages vers le recepteur |
| `5503/UDP` | Retour d'etat du recepteur vers l'emetteur |

## Prerequis

- Android Studio recent.
- JDK 17.
- Android SDK avec `compileSdk 34`.
- Deux appareils Android sur le meme reseau local.
- Android 10 ou plus recent (`minSdk 29`).

## Compilation

Depuis la racine du depot :

```bash
./gradlew assembleDebug
```

Sous Windows :

```powershell
.\gradlew.bat assembleDebug
```

Les APK debug sont generes dans :

```text
sender/build/outputs/apk/debug/
receiver/build/outputs/apk/debug/
```

## Installation

Installer l'application `receiver` sur le smartphone qui affichera le flux.

Installer l'application `sender` sur le smartphone dont l'ecran sera diffuse.

Exemple avec ADB :

```bash
adb install receiver/build/outputs/apk/debug/receiver-debug.apk
adb install sender/build/outputs/apk/debug/sender-debug.apk
```

## Utilisation

1. Connecter les deux smartphones au meme reseau local.
2. Lancer `Stream SBS Receiver` sur le smartphone recepteur.
3. Lancer `Stream SBS Sender` sur le smartphone emetteur.
4. Choisir le profil video si besoin.
5. Appuyer sur `Start Stream`.
6. Accepter la capture d'ecran Android et selectionner l'ecran entier.
7. Le recepteur affiche le flux en mode SBS.

## Controles du recepteur

Le recepteur peut etre pilote avec les boutons de volume :

- Volume haut : activer/desactiver la superposition camera lorsque le menu est ferme.
- Volume bas : ouvrir le menu.
- Volume haut/bas dans le menu : naviguer entre les options.
- Appui long sur volume haut : entrer ou sortir du mode edition.
- Appui long sur volume bas : fermer le menu.

## Reglages disponibles

- Definition du flux video.
- FPS cible.
- Debit video.
- Opacite de la camera.
- Zoom horizontal.
- Zoom vertical.
- Calage horizontal.
- Calage vertical.

## Tests

Le module `common` contient des tests unitaires pour :

- le parsing H.264 Annex B et length-prefixed ;
- la logique de drop de frames basse latence ;
- la serialisation des reglages de rendu.

Lancer les tests :

```bash
./gradlew test
```

Sous Windows :

```powershell
.\gradlew.bat test
```

## Notes techniques

- Le flux video est encode en H.264 AVC.
- Le transport utilise UDP pour privilegier la faible latence.
- Les paquets video incluent des metadonnees de timestamp, resolution, FPS cible et bitrate.
- Le recepteur jette les frames trop anciennes afin de conserver un rendu reactif.
- Le rendu SBS est assure par une vue OpenGL ES qui dessine le flux dans deux viewports.

## Licence

Ce projet est distribue sous licence MIT. Voir le fichier `LICENSE`.
