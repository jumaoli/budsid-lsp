package io.github.jumaoli.budsid;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

/**
 * 把模块配置暴露给被注入的 com.xiaomi.bluetooth 进程。
 * 只读，内容就是 Prefs.configJson()。
 */
public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.jumaoli.budsid.config";
    public static final String URL = "content://" + AUTHORITY + "/config";
    public static final Uri URI = Uri.parse(URL);

    private static final String[] COLUMNS = new String[]{"json"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(COLUMNS);
        String json = readOverride();
        if (json == null) json = Prefs.configJson(getContext());
        c.addRow(new Object[]{json});
        return c;
    }

    /**
     * 调试用：files/override.json 存在时优先使用它。
     * 方便用 adb 推送不同目标代号做对比，普通用户不会创建这个文件。
     */
    private String readOverride() {
        try {
            java.io.File f = new java.io.File(getContext().getFilesDir(), "override.json");
            if (!f.exists()) return null;
            byte[] buf = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n <= 0) break;
                    off += n;
                }
            }
            String s = new String(buf, "UTF-8").trim();
            return s.startsWith("{") ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        return "application/json";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        return null;
    }
}
