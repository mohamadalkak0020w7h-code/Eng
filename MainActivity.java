package com.mohamadalqaaq.alkakadmin;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQ_CREATE_FILE = 2001;
    private static final int REQ_OPEN_FILE = 2002;
    private static final int REQ_OPEN_TREE = 2003;
    private static final int REQ_FILE_CHOOSER = 2004;

    private WebView webView;
    private String pendingJsonPayload = "";
    private String pendingTreeFileName = "ALKAK_Admin_Backup.json";
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new NativeStorageBridge(), "AndroidStorage");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = params.createIntent();
                try {
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    public class NativeStorageBridge {
        @JavascriptInterface
        public boolean isAvailable() {
            return true;
        }

        @JavascriptInterface
        public void saveJsonToUsbOrSd(String fileName, String jsonContent) {
            pendingJsonPayload = jsonContent != null ? jsonContent : "{}";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, fileName != null && !fileName.isEmpty() ? fileName : "ALKAK_Admin_Backup.json");
            startActivityForResult(intent, REQ_CREATE_FILE);
        }

        @JavascriptInterface
        public void saveJsonToFolder(String fileName, String jsonContent) {
            pendingJsonPayload = jsonContent != null ? jsonContent : "{}";
            pendingTreeFileName = fileName != null && !fileName.isEmpty() ? fileName : "ALKAK_Admin_Backup.json";
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQ_OPEN_TREE);
        }

        @JavascriptInterface
        public void pickJsonFromUsbOrSd() {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_OPEN_FILE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] results = (resultCode == RESULT_OK && data != null && data.getData() != null)
                        ? new Uri[]{data.getData()} : null;
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_CREATE_FILE && data.getData() != null) {
            writeUriContent(data.getData(), pendingJsonPayload);
            notifyJs("onNativeSaveResult", true, "تم الحفظ المباشر بنجاح ✓");
        } else if (requestCode == REQ_OPEN_TREE && data.getData() != null) {
            try {
                Uri treeUri = data.getData();
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                androidx.documentfile.provider.DocumentFile pickedDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri);
                if (pickedDir != null) {
                    androidx.documentfile.provider.DocumentFile existing = pickedDir.findFile(pendingTreeFileName);
                    androidx.documentfile.provider.DocumentFile target = existing != null
                            ? existing : pickedDir.createFile("application/json", pendingTreeFileName);
                    if (target != null) {
                        writeUriContent(target.getUri(), pendingJsonPayload);
                        notifyJs("onNativeSaveResult", true, "تم الحفظ داخل المجلد المحدد بنجاح ✓");
                        return;
                    }
                }
            } catch (Exception e) {
                notifyJs("onNativeSaveResult", false, "تعذر الكتابة داخل المجلد: " + e.getMessage());
                return;
            }
            notifyJs("onNativeSaveResult", false, "تعذر إنشاء الملف في المجلد المحدد");
        } else if (requestCode == REQ_OPEN_FILE && data.getData() != null) {
            String content = readUriContent(data.getData());
            if (content != null) {
                String escaped = org.json.JSONObject.quote(content);
                runOnUiThread(() -> webView.evaluateJavascript("window.onNativeFileLoaded && window.onNativeFileLoaded(" + escaped + ");", null));
            } else {
                notifyJs("onNativeSaveResult", false, "تعذر قراءة الملف المحدد");
            }
        }
    }

    private void writeUriContent(Uri uri, String content) {
        try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
            if (os != null) {
                os.write(content.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (Exception ignored) {}
    }

    private String readUriContent(Uri uri) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void notifyJs(String fnName, boolean ok, String msg) {
        String qMsg = org.json.JSONObject.quote(msg);
        runOnUiThread(() -> webView.evaluateJavascript("window." + fnName + " && window." + fnName + "(" + ok + ", " + qMsg + ");", null));
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
