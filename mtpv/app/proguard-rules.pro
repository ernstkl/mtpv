# R8 rules for the release build. The app uses no reflection itself; NanoHTTPD and AndroidX need none.
# Keep line numbers readable in crash reports.
-keepattributes SourceFile,LineNumberTable
