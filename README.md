# MangaLens

Android app that reads text in manga / webtoon images (Korean, Japanese, Chinese, Spanish, French, German, Italian,
Portuguese, Indonesian, Vietnamese), translates it to English and draws the translation over the original text. Built for Android 10 (targetSdk 29, minSdk 24), runs on newer versions too.

## How it works

1. **Images**: take a photo, or pick several from the gallery. Gallery images are sorted by file name (1, 2, 10) and shown
   one under the other like a webtoon strip. Choose the **text language** with the button under the toolbar first.
2. **Detect text**: ML Kit text recognition for the chosen language (bundled in the APK, works offline).
3. **Group**: nearby text blocks are merged into one speech bubble, so sentences are not translated in pieces.
4. **Translate** with the engine you chose (see below).
5. **Overlay**: the old text is painted over with the bubble's background colour and the English text is
   drawn in the same spot with an auto-fitted font size. Tap the strip to flip between originals and translations.
   **Save** writes every translated page as a JPEG to `Pictures/MangaLens`.

## Translators (Translators button, you can download all of them and switch any time)

| Engine | Runs | Size | Notes |
|---|---|---|---|
| Google ML Kit | offline | ~30 MB | Smallest and fastest, most literal. |
| OPUS-MT ko to en | offline | ~120 MB | Small ONNX model, quick, fairly literal. |
| NLLB-200 600M | offline | ~0.9 GB | Better quality, but large and slow. Needs a recent phone with 4 GB+ RAM. |
| Online LLM | online | none | Any OpenAI-compatible `/chat/completions` API plus your own key. Best with context, tone and slang. The whole page is sent in one request. |

Offline model files are downloaded from Hugging Face (`Xenova/opus-mt-ko-en`, `Xenova/nllb-200-distilled-600M`)
into the app's own storage and can be deleted again from the same screen. Keep the Translators screen open
while a download runs; an interrupted download resumes where it stopped.

The online engine sends the detected text to the provider you configure. The API key is stored in the app's
private preferences on the phone.

## Build with GitHub

1. Create a repository and push this folder to it.
2. `.github/workflows/android.yml` runs on every push (or by hand under **Actions > Android CI > Run workflow**).
3. When it finishes, open the run and download the **MangaLens-debug-apk** artifact. It is a debug-signed APK
   you can install directly (allow installs from unknown sources).

The workflow installs Gradle 8.9 itself, so no Gradle wrapper files are needed in the repo. To open the project in
Android Studio, let it sync; if it asks for a wrapper, run `gradle wrapper --gradle-version 8.9` once.

## Known limits

- Tall webtoon strips are read in horizontal slices; a line of text cut by a slice border can be read in two pieces.
- Stylized lettering, sound effects and vertical text are often missed or misread by OCR.
- The offline ONNX engines translate one sentence at a time without a decoder cache, so NLLB can take many seconds per bubble.
- The patch colour is sampled around each bubble; text over detailed artwork will look like a flat sticker.
- Only one language per run; mixed-language pages are not supported. English is always the target.
- OPUS-MT models exist for every listed language except Portuguese; NLLB and ML Kit cover all of them.
