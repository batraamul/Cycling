# R8 rules for the release build.
# Room, Compose and Navigation ship their own consumer rules, so very little is needed here.
# Keep line numbers so crash stack traces from release builds stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
