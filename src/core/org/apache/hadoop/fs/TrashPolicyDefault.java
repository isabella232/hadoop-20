/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.fs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;

/** Provides a <i>trash</i> feature.  Files are moved to a user's trash
 * directory, a subdirectory of their home directory named ".Trash".  Files are
 * initially moved to a <i>current</i> sub-directory of the trash directory.
 * Within that sub-directory their original path is preserved.  Periodically
 * one may checkpoint the current trash and remove older checkpoints.  (This
 * design permits trash management without enumeration of the full trash
 * content, without date support in the filesystem, and without clock
 * synchronization.)
 */
public class TrashPolicyDefault extends TrashPolicy {
  private static final Log LOG =
    LogFactory.getLog(TrashPolicyDefault.class);

  protected static final Path CURRENT = new Path("Current");
  private static final Path TRASH = new Path(".Trash/");  

  private static final FsPermission PERMISSION =
    new FsPermission(FsAction.ALL, FsAction.NONE, FsAction.NONE);

  protected static final DateFormat CHECKPOINT = new SimpleDateFormat("yyMMddHHmmss");
  public static final int MSECS_PER_MINUTE = 60*1000;

  private Path current;
  protected Path homesParent;

  public TrashPolicyDefault() { }

  protected TrashPolicyDefault(Path home, Configuration conf) throws IOException {
    initialize(conf, home.getFileSystem(conf), home);
  }

  @Override
  public void initialize(Configuration conf, FileSystem fs, Path home) {
    this.fs = fs;
    this.trash = new Path(home, TRASH);
    this.homesParent = home.getParent();
    this.current = new Path(trash, CURRENT);
    this.deletionInterval = (long) (conf.getFloat("fs.trash.interval", 60) *
                                    MSECS_PER_MINUTE);

  }
  
  private Path makeTrashRelativePath(Path basePath, Path rmFilePath) {
    return new Path(basePath + rmFilePath.toUri().getPath());
  }

  @Override
  public boolean isEnabled() {
    return (deletionInterval != 0);
  }

  @Override
  public boolean moveToTrash(Path path) throws IOException {
    if (!isEnabled())
      return false;

    if (!path.isAbsolute())                       // make path absolute
      path = new Path(fs.getWorkingDirectory(), path);

    if (!fs.exists(path))                         // check that path exists
      throw new FileNotFoundException(path.toString());

    String qpath = fs.makeQualified(path).toString();

    String pathString = path.toUri().getPath();
    if (pathString.equals("/tmp") || pathString.startsWith("/tmp/")) {
      // temporary files not move to trash
      return false;
    }
    
    if (qpath.startsWith(trash.toString())) {
      return false;                               // already in trash
    }

    if (trash.getParent().toString().startsWith(qpath)) {
      throw new IOException("Cannot move \"" + path +
                            "\" to the trash, as it contains the trash");
    }

    Path trashPath = makeTrashRelativePath(current, path);
    Path baseTrashPath = makeTrashRelativePath(current, path.getParent());
    
    IOException cause = null;

    // try twice, in case checkpoint between the mkdirs() & rename()
    for (int i = 0; i < 2; i++) {
      try {
        if (!fs.mkdirs(baseTrashPath, PERMISSION)) {      // create current
          LOG.warn("Can't create(mkdir) trash directory: "+baseTrashPath);
          return false;
        }
      } catch (IOException e) {
        LOG.warn("Can't create trash directory: "+baseTrashPath);
        cause = e;
        break;
      }
      try {
        // if the target path in Trash already exists, then append with 
        // a current time in millisecs.
        String orig = trashPath.toString();
        
        while(fs.exists(trashPath)) {
          trashPath = new Path(orig + System.currentTimeMillis());
        }
        
        if (fs.rename(path, trashPath))           // move to current trash
          return true;
      } catch (IOException e) {
        cause = e;
      }
    }
    throw (IOException)
      new IOException("Failed to move to trash: "+path).initCause(cause);
  }

  /** {@inheritDoc} */
  @Override
  public boolean moveFromTrash(Path path) throws IOException {

    if (!isEnabled())
      return false;

    if (!path.isAbsolute())
      path = new Path(fs.getWorkingDirectory(), path);

    // don't overwrite existing files
    if (fs.exists(path))
      throw new IOException("Refusing to overwrite existing file " +
                            path.toString());

    // don't restore paths that are actually in the trash
    String qpath = fs.makeQualified(path).toString();
    if (qpath.startsWith(trash.toString())) {
      throw new IOException("Refusing to restore file into the trash");
    }

    // search the trash directories from newest to oldest
    FileStatus[] dirs = null;
    try {
      dirs = fs.listStatus(trash);
    } catch (FileNotFoundException e) {
      // nothing in the trash at all
      return false;
    }
    
    if (null == dirs) {
    	return false;
    }
    
    for (int i = dirs.length-1; i >= 0; i--) {
      // match all the files /path/to/file[NNNNNNNNNNNNN] This will
      // work fine until the year 2286 when the number of digits in
      // a timestamp changes. This is equivalent to:
      //
      //   struct timeval tv;
      //   gettimeofday(&tv, NULL);
      //   printf("%ld%03d\n", tv.tv_sec, (int)tv.tv_usec/1000);
      //
      final Path fromPath = makeTrashRelativePath(dirs[i].getPath(), path);
      FileStatus[] files = fs.globStatus(
        new Path(fromPath.toString() + "*"),
        new PathFilter() {
          private final String regex = fromPath + "[0-9]{13}";
          public boolean accept(Path path) {
            return path.equals(fromPath) || path.toString().matches(regex);
          }
        });

      // nothing matched in this directory
      if (files == null || files.length == 0)
        continue;

      // restore the newest file
      return fs.rename(files[files.length-1].getPath(), path);
    }

    // nothing was found
    return false;
  }

  @Override
  public void createCheckpoint() throws IOException {
    if (!fs.exists(current))                     // no trash, no checkpoint
      return;

    Path checkpoint;
    synchronized (CHECKPOINT) {
      checkpoint = new Path(trash, CHECKPOINT.format(new Date()));
    }

    if (fs.rename(current, checkpoint)) {
      LOG.info("Created trash checkpoint: "+checkpoint.toUri().getPath());
    } else {
      throw new IOException("Failed to checkpoint trash: "+checkpoint);
    }
  }

  @Override
  public void deleteCheckpoint() throws IOException {
    FileStatus[] dirs = null;
    
    try {
      dirs = fs.listStatus(trash);            // scan trash sub-directories
    } catch (FileNotFoundException fnfe) {
      return;
    }

    if (dirs == null) return;

    long now = System.currentTimeMillis();
    for (int i = 0; i < dirs.length; i++) {
      Path path = dirs[i].getPath();
      String dir = path.toUri().getPath();
      String name = path.getName();
      if (name.equals(CURRENT.getName()))         // skip current
        continue;

      long time;
      try {
        synchronized (CHECKPOINT) {
          time = CHECKPOINT.parse(name).getTime();
        }
      } catch (ParseException e) {
        LOG.warn("Unexpected item in trash: "+dir+". Ignoring.");
        continue;
      }

      if ((now - deletionInterval) > time) {
        if (fs.delete(path, true)) {
          LOG.info("Deleted trash checkpoint: "+dir);
        } else {
          LOG.warn("Couldn't delete checkpoint: "+dir+" Ignoring.");
        }
      }
    }
  }

  @Override
  public Path getCurrentTrashDir() {
    return current;
  }

  @Override
  public Runnable getEmptier() throws IOException {
    return new Emptier(getConf());
  }

  private class Emptier implements Runnable {

    private Configuration conf;
    private long emptierInterval;

    Emptier(Configuration conf) throws IOException {
      this.conf = conf;
      this.emptierInterval = (long)
                             (conf.getFloat("fs.trash.checkpoint.interval", 0) *
                              MSECS_PER_MINUTE);
      if (this.emptierInterval > deletionInterval ||
          this.emptierInterval == 0) {
        LOG.warn("The configured interval for checkpoint is " +
                 this.emptierInterval + " minutes." +
                 " Using interval of " + deletionInterval +
                 " minutes that is used for deletion instead");
        this.emptierInterval = deletionInterval;
      }
    }

    public void run() {
      if (emptierInterval == 0)
        return;                                   // trash disabled
      long now = System.currentTimeMillis();
      long end;
      while (true) {
        end = ceiling(now, emptierInterval);
        try {                                     // sleep for interval
          Thread.sleep(end - now);
        } catch (InterruptedException e) {
          break;                                  // exit on interrupt
        }

        try {
          now = System.currentTimeMillis();
          if (now >= end) {

            FileStatus[] homes = null;
            try {
              homes = fs.listStatus(homesParent);         // list all home dirs
            } catch (IOException e) {
              LOG.warn("Trash can't list homes: "+e+" Sleeping.");
              continue;
            }

            if (homes == null) continue;
            
            for (FileStatus home : homes) {         // dump each trash
              if (!home.isDir())
                continue;
              try {
                TrashPolicyDefault trash = new TrashPolicyDefault(home.getPath(), conf);
                trash.deleteCheckpoint();
                trash.createCheckpoint();
              } catch (IOException e) {
                LOG.warn("Trash caught: "+e+". Skipping "+home.getPath()+".");
              } 
            }
          }
        } catch (Exception e) {
          LOG.warn("RuntimeException during Trash.Emptier.run(): ", e); 
        }
      }
      try {
        fs.close();
      } catch(IOException e) {
        LOG.warn("Trash cannot close FileSystem: ", e);
      }
    }

    private long ceiling(long time, long interval) {
      return floor(time, interval) + interval;
    }
    private long floor(long time, long interval) {
      return (time / interval) * interval;
    }
  }
}
