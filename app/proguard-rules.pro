# MAY - Task Manager R8 rules
-keep class dev.may.taskmanager.MayApp { *; }
-keep class dev.may.taskmanager.MainActivity { *; }
-keep class dev.may.taskmanager.root.AppLogCaptureService { *; }
-keep class dev.may.taskmanager.root.LogNotificationReceiver { *; }
-keep class dev.may.taskmanager.root.anomaly.BackgroundAnomalyReceiver { *; }
-keep class dev.may.taskmanager.battery.DrainNotificationReceiver { *; }
-keep class dev.may.taskmanager.network.SpeedMonitorService { *; }

# R8 aggressive size and byte-code optimization
-repackageclasses ''
-allowaccessmodification

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
