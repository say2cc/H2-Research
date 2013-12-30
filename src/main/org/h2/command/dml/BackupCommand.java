/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command.dml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.h2.api.DatabaseEventListener;
import org.h2.command.CommandInterface;
import org.h2.command.Prepared;
import org.h2.constant.ErrorCode;
import org.h2.engine.Constants;
import org.h2.engine.Database;
import org.h2.engine.Session;
import org.h2.expression.Expression;
import org.h2.message.DbException;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.db.MVTableEngine.Store;
import org.h2.result.ResultInterface;
import org.h2.store.FileLister;
import org.h2.store.PageStore;
import org.h2.store.fs.FileUtils;
import org.h2.util.IOUtils;

/**
 * This class represents the statement
 * BACKUP
 */
public class BackupCommand extends Prepared {

    private Expression fileNameExpr;

    public BackupCommand(Session session) {
        super(session);
    }

    public void setFileName(Expression fileName) {
        this.fileNameExpr = fileName;
    }

    @Override
    public int update() {
        String name = fileNameExpr.getValue(session).getString();
        session.getUser().checkAdmin();
        backupTo(name);
        return 0;
    }

    private void backupTo(String fileName) {
        Database db = session.getDatabase();
        if (!db.isPersistent()) {
            throw DbException.get(ErrorCode.DATABASE_IS_NOT_PERSISTENT);
        }
        try {
            Store store = db.getMvStore();
            if (store != null) {
                store.flush();
            }
            String name = db.getName(); //返回E:/H2/baseDir/mydb
            name = FileUtils.getName(name); //返回mydb(也就是只取简单文件名
            //生成fileName表示的文件，如果已存在则覆盖原有的，也就是文件为空
            OutputStream zip = FileUtils.newOutputStream(fileName, false);
            ZipOutputStream out = new ZipOutputStream(zip);
            db.flush();
            String fn = db.getName() + Constants.SUFFIX_PAGE_FILE; //返回E:/H2/baseDir/mydb.h2.db
            backupPageStore(out, fn, db.getPageStore());
            // synchronize on the database, to avoid concurrent temp file
            // creation / deletion / backup
            String base = FileUtils.getParent(fn);
            synchronized (db.getLobSyncObject()) {
                String prefix = db.getDatabasePath(); //返回E:/H2/baseDir/mydb
                String dir = FileUtils.getParent(prefix); //返回E:/H2/baseDir
                dir = FileLister.getDir(dir); //返回E:/H2/baseDir
                ArrayList<String> fileList = FileLister.getDatabaseFiles(dir, name, true);
                
                //".lob.db"和".mv.db"文件也备份到fileName表示的文件中，
                //也就是说BACKUP TO 'E:/H2/baseDir/myBackup'这样的SQL除了把基本的“.h2.db”备份外，
                //还备份".lob.db"和".mv.db"文件
                for (String n : fileList) {
                    if (n.endsWith(Constants.SUFFIX_LOB_FILE)) { //备份".lob.db"文件
                        backupFile(out, base, n);
                    }
                    if (n.endsWith(Constants.SUFFIX_MV_FILE)) { //备份".mv.db"文件
                        MVStore s = store.getStore();
                        boolean before = s.getReuseSpace();
                        s.setReuseSpace(false);
                        try {
                            InputStream in = store.getInputStream();
                            backupFile(out, base, n, in);
                        } finally {
                            s.setReuseSpace(before);
                        }
                    }
                }
            }
            out.close();
            zip.close();
        } catch (IOException e) {
            throw DbException.convertIOException(e, fileName);
        }
    }

    private void backupPageStore(ZipOutputStream out, String fileName, PageStore store) throws IOException {
        Database db = session.getDatabase();
        fileName = FileUtils.getName(fileName); //fileName = E:/H2/baseDir/mydb.h2.db，然后变成"mydb.h2.db"
        out.putNextEntry(new ZipEntry(fileName));
        int pos = 0;
        try {
            store.setBackup(true);
            while (true) {
                pos = store.copyDirect(pos, out); //一页一页的copy
                if (pos < 0) {
                    break;
                }
                int max = store.getPageCount();
                db.setProgress(DatabaseEventListener.STATE_BACKUP_FILE, fileName, pos, max);
            }
        } finally {
            store.setBackup(false);
        }
        out.closeEntry();
    }

    private static void backupFile(ZipOutputStream out, String base, String fn) throws IOException {
        InputStream in = FileUtils.newInputStream(fn);
        backupFile(out, base, fn, in);
    }

    private static void backupFile(ZipOutputStream out, String base, String fn, InputStream in) throws IOException {
        String f = FileUtils.toRealPath(fn); //返回E:/H2/baseDir/mydb.mv.db
        base = FileUtils.toRealPath(base); //返回E:/H2/baseDir
        if (!f.startsWith(base)) {
            DbException.throwInternalError(f + " does not start with " + base);
        }
        f = f.substring(base.length()); //返回/mydb.mv.db
        f = correctFileName(f); //返回mydb.mv.db
        out.putNextEntry(new ZipEntry(f));
        IOUtils.copyAndCloseInput(in, out);
        out.closeEntry();
    }

    @Override
    public boolean isTransactional() {
        return true;
    }

    /**
     * Fix the file name, replacing backslash with slash.
     *
     * @param f the file name
     * @return the corrected file name
     */
    public static String correctFileName(String f) {
        f = f.replace('\\', '/');
        if (f.startsWith("/")) {
            f = f.substring(1);
        }
        return f;
    }

    @Override
    public boolean needRecompile() {
        return false;
    }

    @Override
    public ResultInterface queryMeta() {
        return null;
    }

    @Override
    public int getType() {
        return CommandInterface.BACKUP;
    }

}
