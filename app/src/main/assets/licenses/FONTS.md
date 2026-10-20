# Bundled fonts

Both fonts are under the SIL Open Font License 1.1 (licence texts next to this file). Neither declares a Reserved Font Name.

- **Manrope** (`res/font/manrope_*.ttf`): static Medium 500, SemiBold 600, Bold 700 and ExtraBold 800 instances of `ofl/manrope/Manrope[wght].ttf` from https://github.com/google/fonts. That repo ships only the variable font.
- **Roboto Medium** (`res/font/roboto_medium.ttf`): a static wght=500, wdth=100 instance of `ofl/roboto/Roboto[wdth,wght].ttf` from the same repo. It is used only for the "Continue with Google" button, as Google's sign-in branding guidelines require, so the button doesn't depend on the phone's system font.

The static files were made with fontTools:

```
fonttools varLib.instancer Manrope[wght].ttf wght=700 --static --update-name-table -o manrope_bold.ttf
fonttools varLib.instancer Roboto[wdth,wght].ttf wght=500 wdth=100 --static --update-name-table -o roboto_medium.ttf
```
