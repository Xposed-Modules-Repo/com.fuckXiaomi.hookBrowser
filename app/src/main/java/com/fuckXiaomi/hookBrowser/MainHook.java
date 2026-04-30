package com.fuckXiaomi.hookBrowser;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    
    private static final String CONFIG_PATH = "/data/local/tmp/browser.txt";
    
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkgName = lpparam.packageName;
        
        if ("com.xiaomi.aicr".equals(pkgName)) {
            XposedBridge.log("成功注入 小米澎湃AI引擎 进程！");
            hookAicr(lpparam);
        } else if ("com.miui.voiceassist".equals(pkgName)) {
            XposedBridge.log("成功注入 超级小爱 进程！");
            hookVoiceAssist(lpparam);
        }
    }
    
    // ========================================================
    // 1：[小米澎湃AI引擎]
    // ========================================================
    private void hookAicr(XC_LoadPackage.LoadPackageParam lpparam) {
        String targetClass = "com.xiaomi.aicr.copydirect.util.SmartPasswordUtils";
        
        XposedHelpers.findAndHookMethod(targetClass, lpparam.classLoader, "jumpToXiaoMiBrowser",
                Context.class, String.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = (Context) param.args[0];
                        String str = (String) param.args[1];
                        
                        Intent intent = new Intent("android.intent.action.VIEW");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        intent.putExtra("open_source", "clipboard_open");
                        
                        if (str.startsWith("http")) intent.setData(Uri.parse(str));
                        else intent.setData(Uri.parse("https://" + str));
                        
                        String userBrowserPkg = getCustomBrowserPkg();
                        if (userBrowserPkg != null && !userBrowserPkg.isEmpty()) {
                            intent.setPackage(userBrowserPkg);
                        } else {
                            intent.setPackage(null);
                        }
                        context.startActivity(intent);
                        return null;
                    }
                });
        
        XposedHelpers.findAndHookMethod(targetClass, lpparam.classLoader, "isInstallForApp",
                Context.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if ("com.android.browser".equals(param.args[1])) param.setResult(true);
                    }
                });
        
        XposedHelpers.findAndHookMethod("android.app.NotificationManager", lpparam.classLoader, "notify",
                int.class, android.app.Notification.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int id = (int) param.args[0];
                        android.app.Notification notification = (android.app.Notification) param.args[1];
                        if (id == 111 && notification != null && notification.extras != null) {
                            if (notification.extras.getString("copyText") != null) {
                                String customPkg = getCustomBrowserPkg();
                                if (customPkg == null || customPkg.isEmpty()) return;
                                try {
                                    Context context = android.app.AndroidAppHelper.currentApplication();
                                    Icon newIcon = getCustomAppIcon(context, customPkg);
                                    XposedHelpers.setObjectField(notification, "mSmallIcon", newIcon);
                                    XposedHelpers.setObjectField(notification, "mLargeIcon", newIcon);
                                    notification.extras.putParcelable("miui.appIcon", newIcon);
                                    Bundle focusPics = notification.extras.getBundle("miui.focus.pics");
                                    if (focusPics != null) {
                                        focusPics.putParcelable("miui.focus.pic_image", newIcon);
                                        focusPics.putParcelable("miui.land.pic_image", newIcon);
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                });
    }
    
    // ========================================================
    // 2：[超级小爱]
    // ========================================================
    private void hookVoiceAssist(XC_LoadPackage.LoadPackageParam lpparam) {
        String targetClass = "com.xiaomi.voiceassistant.utils.b2";
        
        try {
            Class<?> b2Class = XposedHelpers.findClass(targetClass, lpparam.classLoader);
            
            for (java.lang.reflect.Method method : b2Class.getDeclaredMethods()) {
                
                // 1. 查岗拦截 (欺骗系统验证)
                if ("isIntentAvailable".equals(method.getName()) && method.getReturnType() == boolean.class) {
                    XposedBridge.hookMethod(method, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            for (Object arg : param.args) {
                                if (arg instanceof Intent) {
                                    Intent intent = (Intent) arg;
                                    if ("com.android.browser".equals(intent.getPackage()) ||
                                            (intent.getComponent() != null && intent.getComponent().getPackageName().contains("browser"))) {
                                        
                                        String customPkg = getCustomBrowserPkg();
                                        if (customPkg != null && !customPkg.isEmpty()) {
                                            intent.setPackage(customPkg);
                                        } else {
                                            intent.setPackage(null);
                                        }
                                        intent.setComponent(null); // 彻底抹除小米的固定组件名
                                    }
                                }
                            }
                            return true;
                        }
                    });
                }
                
                // 2. 启动拦截与 URL 纯净提取
                if ("startActivity".equals(method.getName()) && method.getReturnType() == boolean.class) {
                    XposedBridge.hookMethod(method, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            Context ctx = null;
                            Intent intent = null;
                            
                            for (Object arg : param.args) {
                                if (arg instanceof Context) ctx = (Context) arg;
                                if (arg instanceof Intent) intent = (Intent) arg;
                            }
                            
                            if (ctx != null && intent != null) {
                                String customPkg = getCustomBrowserPkg();
                                
                                // A. 斩断包名限制
                                if (customPkg != null && !customPkg.isEmpty()) {
                                    if ("com.android.browser".equals(intent.getPackage()) || customPkg.equals(intent.getPackage())) {
                                        intent.setPackage(customPkg);
                                        intent.setComponent(null);
                                    }
                                } else {
                                    if ("com.android.browser".equals(intent.getPackage())) {
                                        intent.setPackage(null);
                                        intent.setComponent(null);
                                    }
                                }
                                
                                // B. 精确提取目标网址
                                Uri data = intent.getData();
                                if (data != null) {
                                    String scheme = data.getScheme();
                                    if (scheme != null && (scheme.startsWith("mi") || scheme.equals("intent"))) {
                                        String realUrl = null;
                                        
                                        // 优先级1：核心参数提取
                                        String[] targetKeys = {"url", "query", "q", "link", "text"};
                                        for (String targetKey : targetKeys) {
                                            try {
                                                String val = data.getQueryParameter(targetKey);
                                                if (val != null && !val.trim().isEmpty()) {
                                                    realUrl = val.trim();
                                                    break;
                                                }
                                            } catch (Exception ignored) {}
                                        }
                                        
                                        // 优先级2：模糊嗅探疑似网址的参数
                                        if (realUrl == null) {
                                            try {
                                                for (String key : data.getQueryParameterNames()) {
                                                    String val = data.getQueryParameter(key);
                                                    if (val != null) {
                                                        val = val.trim();
                                                        if (val.startsWith("http") || (val.contains(".") && !val.contains(" "))) {
                                                            realUrl = val;
                                                            break;
                                                        }
                                                    }
                                                }
                                            } catch (Exception ignored) {}
                                        }
                                        
                                        // 优先级3：翻找 Extras 包裹
                                        if (realUrl == null && intent.getExtras() != null) {
                                            Bundle extras = intent.getExtras();
                                            for (String key : extras.keySet()) {
                                                Object val = extras.get(key);
                                                if (val instanceof String) {
                                                    String s = ((String) val).trim();
                                                    if (s.startsWith("http") || (s.contains(".") && !s.contains(" ") && s.length() > 4)) {
                                                        realUrl = s;
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                        
                                        // C. 网址标准化并重挂载
                                        if (realUrl != null && !realUrl.isEmpty()) {
                                            realUrl = java.net.URLDecoder.decode(realUrl, "UTF-8");
                                            if (!realUrl.startsWith("http://") && !realUrl.startsWith("https://")) {
                                                realUrl = "https://" + realUrl;
                                            }
                                            intent.setData(Uri.parse(realUrl));
                                            XposedBridge.log("超级小爱：成功净化并启动网址 -> " + realUrl);
                                        }
                                    }
                                }
                                
                                // D. 原生接管发射
                                try {
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    ctx.startActivity(intent);
                                    return true;
                                } catch (Exception e) {
                                    XposedBridge.log("超级小爱：原生启动异常 -> " + e.getMessage());
                                    return false;
                                }
                            }
                            return false;
                        }
                    });
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("Hook b2 失败: " + t.getMessage());
        }
    }
    
    // ========================================================
    // 工具方法区
    // ========================================================
    // 读取单行纯文本配置文件
    private String getCustomBrowserPkg() {
        File configFile = new File(CONFIG_PATH);
        if (configFile.exists() && configFile.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(configFile))) {
                String line = br.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    return line.trim();
                }
            } catch (Exception ignored) {}
        }
        return "";
    }
    
    // 获取第三方应用的 Icon 对象
    private Icon getCustomAppIcon(Context context, String pkgName) throws PackageManager.NameNotFoundException {
        PackageManager pm = context.getPackageManager();
        Drawable customAppIcon = pm.getApplicationIcon(pkgName);
        Bitmap bitmap;
        if (customAppIcon instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) customAppIcon).getBitmap();
        } else {
            int width = Math.max(customAppIcon.getIntrinsicWidth(), 1);
            int height = Math.max(customAppIcon.getIntrinsicHeight(), 1);
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            customAppIcon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            customAppIcon.draw(canvas);
        }
        return Icon.createWithBitmap(bitmap);
    }
}