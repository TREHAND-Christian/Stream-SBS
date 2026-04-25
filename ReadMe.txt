Stream-SBS-5.5
==============

Cette application est destinee principalement a afficher la vue camera d'un drone
dans un casque VR maison.

Contexte d'utilisation
----------------------

Le systeme utilise deux smartphones Android :

1. Smartphone Sender : Samsung A14
   - Connecte a la manette du drone DJI Mini 2 SE.
   - Affiche normalement l'application DJI Fly.
   - Capture son propre ecran avec MediaProjection.
   - Encode et envoie l'image de l'ecran vers le Receiver en streaming local.
   - Sert aussi a regler le casque et le rendu du Receiver.

2. Smartphone Receiver : Samsung A7
   - Installe dans le casque VR maison.
   - Recoit le flux video envoye par le Sender.
   - Affiche l'ecran du A14, donc l'application DJI Fly et la vue camera du drone.
   - Affiche le flux en mode adapte au casque, notamment en SBS.
   - Peut afficher sa propre camera en surimpression avec une opacite reglable.

Role de l'overlay camera du Receiver
------------------------------------

Le Receiver etant dans le casque, son ecran tactile n'est pas accessible.
L'option camera avec opacite permet de voir partiellement l'environnement reel :

- la manette du drone,
- les mains,
- l'ecran du Sender,
- les controles physiques accessibles.

Cette fonction sert de passthrough partiel pour utiliser le casque sans devoir le
retirer.

Contraintes importantes
-----------------------

- Sur le Receiver, l'ecran tactile n'est pas accessible une fois le telephone dans
  le casque.
- Les seuls boutons accessibles sur le Receiver sont Power, Vol+ et Vol-.
- Le Sender est l'interface principale pour configurer le Receiver.
- La latence doit rester faible pour que le pilotage du drone reste utilisable.
- Les reglages doivent tenir sur un seul ecran, sans defilement.
- Les ecrans de l'interface Sender doivent etre separes :
  - mode normal : statut, demarrage du flux, reglages video du flux,
  - mode reglages : zoom, offsets, mode lunettes/tablette, camera et opacite.

Modules du projet
-----------------

- sender
  Application installee sur le A14.
  Elle capture l'ecran du telephone, envoie le flux video au Receiver et permet de
  modifier les reglages de rendu du casque.

- receiver
  Application installee sur le A7.
  Elle recoit le flux video, l'affiche dans le casque VR et gere l'overlay camera.

- common
  Code partage entre Sender et Receiver : protocoles reseau, ports, configuration
  de rendu, paquets video.

Packages Android
----------------

- Sender : com.treha.streamsbs55.sender
- Receiver : com.treha.streamsbs55.receiver
- Common : com.treha.streamsbs55.common

Projet actif
------------

Le seul dossier de travail a utiliser pour ce projet est :

C:\Users\treha\Desktop\Stream-SBS-5.5
