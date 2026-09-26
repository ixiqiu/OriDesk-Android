# 本工程当前 isMinifyEnabled = false，本文件只是占位：
# 一旦开启混淆，WebView 的 @JavascriptInterface（若有）与反射调用需要 keep 规则。
# 留着它可以让 build.gradle.kts 的 proguardFiles 引用始终有效，避免开启混淆时
# 才发现文件不存在。
