# Jumura — Traduction du prêche en direct

Application Android qui **capte la voix de l'imam** pendant la khoutba (prêche du vendredi),
la **transcrit** puis la **traduit en français à l'écrit, en temps réel** — avec une traduction
*intelligente* (fidèle au sens, jamais mot-à-mot). Pensée pour l'arabe littéraire, la **darija**
maghrébine et le français, y compris quand l'imam est éloigné.

## Comment ça marche
1. **Capture** — `AudioRecord` 16 kHz, source `VOICE_RECOGNITION`, réducteur de bruit + AGC
   matériels, gain logiciel réglable. Découpage automatique sur les silences (fins de phrases).
2. **Transcription** — Groq **Whisper large v3** (multilingue, détection auto de la langue).
3. **Traduction** — Groq **gpt-oss-120b**, prompt expert « khoutba » → français clair et fidèle.
4. **Affichage** — transcript qui défile, gros texte lisible, texte original optionnel.

Tout tourne sur le téléphone + l'API **Groq gratuite**. Aucun PC en mosquée.

## Mettre en place le build cloud (comme Jarvis)
Le PC n'a pas besoin d'Android Studio : l'APK est compilé par **GitHub Actions**.

1. Créer un dépôt GitHub **public** : `HeavenWays/JumuraTraduction`.
2. Dans le dépôt : **Settings → Secrets and variables → Actions → New repository secret**
   - Nom : `GROQ_API_KEY`
   - Valeur : ta clé Groq (`gsk_…`, gratuite sur console.groq.com).
3. Pousser ce dossier (`git push`). L'action compile et publie une **Release `vX`**.
4. Installer l'APK depuis la Release. Les mises à jour suivantes se font **dans l'app**
   (bouton « Installer »), sans désinstaller (signature stable).

> La clé peut aussi être saisie directement dans l'app (Réglages) — pratique si le secret CI
> n'est pas configuré. Elle n'est **jamais** écrite dans le code source.

## Permissions
- **Micro** (obligatoire) — capter l'imam.
- **Notifications** — service de premier plan (écoute écran verrouillé).
- **Installer des applications inconnues** — mise à jour intégrée.

## Réglages utiles
- **Sensibilité micro** : monte-la si l'imam est loin.
- **Langue de l'imam** : « Arabe / Darija » fiabilise la darija en salle bruyante ; « Auto » sinon.
- **Taille du texte**, **texte original**, **écran toujours allumé**.
