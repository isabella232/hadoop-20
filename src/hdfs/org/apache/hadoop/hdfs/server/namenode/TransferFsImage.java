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
package org.apache.hadoop.hdfs.server.namenode;

import org.apache.commons.logging.*;

import java.io.*;
import java.net.*;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Map;
import java.lang.Math;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.util.DataTransferThrottler;
import org.apache.hadoop.hdfs.server.namenode.SecondaryNameNode.ErrorSimulator;
import org.apache.hadoop.io.MD5Hash;

/**
 * This class provides fetching a specified file from the NameNode.
 */
class TransferFsImage implements FSConstants {
  public final static String CONTENT_LENGTH = "Content-Length";
  public static final Log LOG = LogFactory.getLog(TransferFsImage.class.getName());

  private boolean isGetImage;
  private boolean isGetEdit;
  private boolean isGetEditNew;
  private boolean isPutImage;
  private int remoteport;
  private String machineName;
  private CheckpointSignature token;
  
  /**
   * File downloader.
   * @param pmap key=value[] map that is passed to the http servlet as 
   *        url parameters
   * @param request the object from which this servelet reads the url contents
   * @param response the object into which this servelet writes the url contents
   * @throws IOException
   */
  public TransferFsImage(Map<String,String[]> pmap,
                         HttpServletRequest request,
                         HttpServletResponse response
                         ) throws IOException {
    isGetImage = isGetEdit = isPutImage = false;
    remoteport = 0;
    machineName = null;
    token = null;

    for (Iterator<String> it = pmap.keySet().iterator(); it.hasNext();) {
      String key = it.next();
      if (key.equals("getimage")) { 
        isGetImage = true;
      } else if (key.equals("getedit")) { 
        isGetEdit = true;
      } else if (key.equals("geteditnew")) { 
        isGetEditNew = true;
      } else if (key.equals("putimage")) { 
        isPutImage = true;
      } else if (key.equals("port")) { 
        remoteport = new Integer(pmap.get("port")[0]).intValue();
      } else if (key.equals("machine")) { 
        machineName = pmap.get("machine")[0];
      } else if (key.equals("token")) { 
        token = new CheckpointSignature(pmap.get("token")[0]);
      }
    }

    int numGets = (isGetImage?1:0) + (isGetEdit?1:0) + (isGetEditNew?1:0);
    if ((numGets > 1) || (numGets == 0) && !isPutImage) {
      throw new IOException("Illegal parameters to TransferFsImage");
    }
  }

  boolean getEdit() {
    return isGetEdit;
  }
  
  boolean getEditNew() {
    return isGetEditNew;
  }

  boolean getImage() {
    return isGetImage;
  }

  boolean putImage() {
    return isPutImage;
  }

  CheckpointSignature getToken() {
    return token;
  }

  String getInfoServer() throws IOException{
    if (machineName == null || remoteport == 0) {
      throw new IOException ("MachineName and port undefined");
    }
    return machineName + ":" + remoteport;
  }

  /**
   * A server-side method to respond to a getfile http request
   * Copies the contents of the local file into the output stream.
   */
  static void getFileServer(OutputStream outstream, File localfile, DataTransferThrottler throttler) 
    throws IOException {
    byte buf[] = new byte[BUFFER_SIZE];
    FileInputStream infile = null;
    long totalReads = 0, totalSends = 0;
    try {
      infile = new FileInputStream(localfile);
      if (ErrorSimulator.getErrorSimulation(2)
          && localfile.getAbsolutePath().contains("secondary")) {
        // throw exception only when the secondary sends its image
        throw new IOException("If this exception is not caught by the " +
            "name-node fs image will be truncated.");
      }
      
      if (ErrorSimulator.getErrorSimulation(3)
          && localfile.getAbsolutePath().contains("fsimage")) {
          // Test sending image shorter than localfile
          long len = localfile.length();
          buf = new byte[(int)Math.min(len/2, BUFFER_SIZE)];
          // This will read at most half of the image
          // and the rest of the image will be sent over the wire
          infile.read(buf);
      }
      int num = 1;
      while (num > 0) {
        long startRead = System.currentTimeMillis();
        num = infile.read(buf);
        if (num <= 0) {
          break;
        }
        outstream.write(buf, 0, num);
        if (throttler != null) {
          throttler.throttle(num);
        }
      }
    } finally {
      if (infile != null) {
        infile.close();
      }
    }
  }

  /**
   * Client-side Method to fetch file from a server
   * Copies the response from the URL to a list of local files.
   * 
   * @Return a digest of the received file if getChecksum is true
   */
  static MD5Hash getFileClient(String fsName, String id, File[] localPath,
      boolean getChecksum)
    throws IOException {
    byte[] buf = new byte[BUFFER_SIZE];
    StringBuffer str = new StringBuffer("http://"+fsName+"/getimage?");
    str.append(id);

    //
    // open connection to remote server
    //
    URL url = new URL(str.toString());
    URLConnection connection = url.openConnection();
    if (localPath == null) {
      // Putting the image back
      connection.setReadTimeout(2 * 60 * 60 * 1000); // 2 hours to upload
    } else {
      connection.setReadTimeout(10 * 60 * 1000); // 10 minute read timeout
    }
    long advertisedSize;
    String contentLength = connection.getHeaderField(CONTENT_LENGTH);
    if (contentLength != null) {
      advertisedSize = Long.parseLong(contentLength);
    } else {
      throw new IOException(CONTENT_LENGTH + " header is not provided " +
                            "by the namenode when trying to fetch " + str);
    }
    long received = 0;
    InputStream stream = connection.getInputStream();
    MessageDigest digester = null;
    if (getChecksum) {
      digester = MD5Hash.getDigester();
      stream = new DigestInputStream(stream, digester);
    }
    FileOutputStream[] output = null;
    
    try {
      if (localPath != null) {
        output = new FileOutputStream[localPath.length];
        for (int i = 0; i < output.length; i++) {
          output[i] = new FileOutputStream(localPath[i]);
        }
      }
      int num = 1;
      while (num > 0) {
        num = stream.read(buf);
        if (num > 0 && localPath != null) {
          received += num;
          for (int i = 0; i < output.length; i++) {
            output[i].write(buf, 0, num);
          }
        }
      }
    } finally {
      stream.close();
      if (output != null) {
        for (int i = 0; i < output.length; i++) {
          if (output[i] != null) {
            output[i].close();
          }
        }
      }
      if (received != advertisedSize) {
        throw new IOException("File " + str + " received length " + received +
                              " is not of the advertised size " +
                              advertisedSize);
      }
    }
    return digester==null ? null : new MD5Hash(digester.digest());
  }
}
