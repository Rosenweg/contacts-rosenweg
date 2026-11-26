<p align="center"><img src="assets/logo/rosenweg-kontakte-512.png" width="96" alt="Logo"></p>
<h1 align="center">Rosenweg Kontakte</h1>
<p align="center">Adressbuch für die Rosenweg-Smartphones – mit dem Telefonbuch der Siedlung.</p>
<p align="center"><a href="https://www.rosenweg4303.ch/apps">Herunterladen</a> · <a href="docs/anleitung.md">Anleitung</a> · <a href="docs/entwickeln.md">Für Entwickler</a></p>

## Was die App kann

- Kontakte anzeigen, suchen, anlegen und bearbeiten.
- Auf geteilten Telefonen ist das Telefonbuch der Siedlung schon drin.
- Auf geteilten Telefonen hat jede Person ihre eigenen Einstellungen.
- Hält auf dem eigenen Handy alle Rosenweg-Apps aktuell (Rosenweg Updater).

## Plattformen

Android.

## Entwickeln

Android-App (Kotlin, Gradle) auf Basis von [commons-rosenweg](https://github.com/Rosenweg/commons-rosenweg).
`./gradlew assembleFossRelease` – Bau, Signatur, Logo und Veröffentlichung stehen in
[docs/entwickeln.md](docs/entwickeln.md).

## Herkunft und Lizenz

Basiert auf [Fossify Contacts](https://github.com/FossifyOrg/Contacts). Herzlichen Dank an das Fossify-Team und alle Mitwirkenden für die solide Grundlage.
GPL-3.0, siehe [LICENSE](LICENSE).
