# DDmusic 混淆规则（release 已开 R8）
# 业务代码保留类名（项目自用、代码量小，混淆收益低且难调试；R8 仍做裁剪与优化）
-keep class com.dengdeng.music.** { *; }

# org.json 由 android.jar 提供，无需额外 keep
# Coil / Media3 / DataStore 均自带 consumer proguard 规则，无需额外配置
