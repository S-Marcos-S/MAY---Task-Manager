# MAY - Task Manager R8 rules
-keep class dev.may.taskmanager.MayApp { *; }
-keep class dev.may.taskmanager.root.AppLogCaptureService { *; }
-keep class dev.may.taskmanager.root.LogNotificationReceiver { *; }
-keep class dev.may.taskmanager.root.anomaly.BackgroundAnomalyReceiver { *; }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
