NOVA 6.0 wake/command model

The source ZIP intentionally does not contain the large Vosk binary model.
The included GitHub Actions workflow downloads vosk-model-small-en-us-0.15 into app/src/main/assets/model-en at build time and validates graph/words.txt and am/final.mdl before compiling.

For a local manual build, download the same Vosk model, extract it, rename the folder to model-en, and place it under app/src/main/assets/.
