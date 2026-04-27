package com.fuckXiaomi.hookBrowser;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import android.os.Bundle;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.xiaomi.aicr")) {
            return;
        }
        
        XposedBridge.log("成功注入小米AI识别进程！(修复Binder死锁极简版)");
        
        String targetClass = "com.xiaomi.aicr.copydirect.util.SmartPasswordUtils";
        
        // ========================================================
        // 1. 核心拦截：只在这个最终方法里“偷梁换柱”
        // ========================================================
        XposedHelpers.findAndHookMethod(
                targetClass,
                lpparam.classLoader,
                "jumpToXiaoMiBrowser",
                Context.class,
                String.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = (Context) param.args[0];
                        String str = (String) param.args[1];
                        
                        Intent intent = new Intent("android.intent.action.VIEW");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // 268435456
                        intent.putExtra("open_source", "clipboard_open");
                        
                        if (str.startsWith("http")) {
                            intent.setData(Uri.parse(str));
                        } else {
                            intent.setData(Uri.parse("https://" + str));
                        }
                        
                        // 动态读取你的配置文件
                        String userBrowserPkg = getCustomBrowserPkg();
                        
                        if (userBrowserPkg != null && !userBrowserPkg.isEmpty()) {
                            intent.setPackage(userBrowserPkg);
                            XposedBridge.log("已强行注入目标浏览器: " + userBrowserPkg);
                        } else {
                            XposedBridge.log("未检测到配置文件，准备唤起系统选择器");
                        }
                        
                        context.startActivity(intent);
                        return null;
                    }
                }
        );
        
        // ========================================================
        // 2. 彻底斩断 Binder 死循环：拦截最底层的安装检查
        // ========================================================
        XposedHelpers.findAndHookMethod(
                targetClass,
                lpparam.classLoader,
                "isInstallForApp",
                Context.class,
                String.class, // 注意：这里拦截的是带有 String 的两个参数的方法！
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String pkgName = (String) param.args[1];
                        // 只要引擎想检查官方浏览器，立马回答"安装了"，阻止底层发起通信！
                        if ("com.android.browser".equals(pkgName)) {
                            param.setResult(true);
                        }
                    }
                }
        );
        // ========================================================
        // 3. 在通知发送前的最后一刻，替换掉包裹里的图标！
        // ========================================================
        XposedHelpers.findAndHookMethod(
                "android.app.NotificationManager", // 拦截安卓原生的通知管理器
                lpparam.classLoader,
                "notify",
                int.class,
                android.app.Notification.class, // 拦截发送的 Notification 对象
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int id = (int) param.args[0];
                        android.app.Notification notification = (android.app.Notification) param.args[1];
                        
                        // 111 是我们在小米源码里看到的 NOTIFICATION_ID
                        // 只有是这个 ID 的通知，我们才去插手，避免影响系统其他正常通知
                        if (id == 111 && notification != null) {
                            
                            // 为了确认这确实是复制网址的通知，我们可以检查它的 extras
                            Bundle extras = notification.extras;
                            if (extras != null && extras.getString("copyText") != null) {
                                
                                String customPkg = getCustomBrowserPkg();
                                if (customPkg != null && !customPkg.isEmpty()) {
                                    try {
                                        // 1. 获取第三方浏览器的真实应用图标
                                        // 注意这里的获取上下文可能有点绕，如果报错，我们可以传入固定的包名或者不获取
                                        // 由于这个 Hook 是在当前进程触发的，我们可以借用 AndroidAppHelper
                                        android.content.Context context = android.app.AndroidAppHelper.currentApplication();
                                        android.content.pm.PackageManager pm = context.getPackageManager();
                                        android.graphics.drawable.Drawable customAppIcon = pm.getApplicationIcon(customPkg);
                                        
                                        // 2. 转换为 Bitmap
                                        android.graphics.Bitmap bitmap = null;
                                        if (customAppIcon instanceof android.graphics.drawable.BitmapDrawable) {
                                            bitmap = ((android.graphics.drawable.BitmapDrawable) customAppIcon).getBitmap();
                                        } else {
                                            int width = Math.max(customAppIcon.getIntrinsicWidth(), 1);
                                            int height = Math.max(customAppIcon.getIntrinsicHeight(), 1);
                                            bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
                                            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                                            customAppIcon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                                            customAppIcon.draw(canvas);
                                        }
                                        
                                        // 3. 转换为 Icon 对象
                                        android.graphics.drawable.Icon newIcon = android.graphics.drawable.Icon.createWithBitmap(bitmap);
                                        
                                        // 4. 暴力替换通知里的所有关键图标！
                                        // 替换小图标 (Small Icon)
                                        XposedHelpers.setObjectField(notification, "mSmallIcon", newIcon);
                                        // 替换大图标 (Large Icon)
                                        XposedHelpers.setObjectField(notification, "mLargeIcon", newIcon);
                                        
                                        // 替换小米特有的 Bundle 里的图标 (这是小米用来显示在胶囊或悬浮泡上的)
                                        extras.putParcelable("miui.appIcon", newIcon);
                                        Bundle miuiFocusPics = extras.getBundle("miui.focus.pics");
                                        if (miuiFocusPics != null) {
                                            miuiFocusPics.putParcelable("miui.focus.pic_image", newIcon);
                                            miuiFocusPics.putParcelable("miui.land.pic_image", newIcon);
                                        }
                                        
                                        XposedBridge.log("成功替换通知栏悬浮窗图标为: " + customPkg);
                                        
                                    } catch (Exception e) {
                                        XposedBridge.log("替换图标失败: " + e.getMessage());
                                    }
                                }
                            }
                        }
                    }
                }
        );
    }
    
    // 纯文本文件读取逻辑
    private String getCustomBrowserPkg() {
        File configFile = new File("/data/local/tmp/mi_browser_hook.txt");
        if (configFile.exists() && configFile.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(configFile))) {
                String line = br.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    return line.trim();
                }
            } catch (Exception e) {
                XposedBridge.log("读取配置文件失败: " + e.getMessage());
            }
        }
        return "";
    }
}