#HDMI Driver Notes 10/2017

- If `open()` fails on the native driver, any changes to the view heirarchy after this crashes the app.
- Calling `.release()` doesn't help.
