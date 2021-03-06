/**
 *  RedirectUnshortener
 *  Copyright 08.03.2015 by Michael Peter Christen, @0rb1t3r
 *  This class is the android version from the original file,
 *  taken from the loklak_server project. It may be slightly different.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */


package org.loklak.harvester;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URL;


public class RedirectUnshortener {

    private final static String[] workingHosts = new String[] {
            "bbc.in",
            "fb.me",
            "wp.me",
            "j.mp",
            "t.co",
            "bit.ly",
            "ift.tt",
            "goo.gl",
            "tinyurl.com",
            "ow.ly",
            "tiny.cc",
            "bit.do",
            "amzn.to",
            "tmblr.co",
            "tumblr.com",
            "www.tumblr.com"
    };

    private final static String[] untestedHosts = new String[] {
            "is.gd",
            "ta.gd",
            "cli.gs",
            "sURL.co.uk",
            "y.ahoo.it",
            "yi.tl",
            "su.pr",
            "Fwd4.Me",
            "budurl.com",
            "snipurl.com",
            "igg.me",
            "twiza.ru"
    };

    public static String unShorten(String urlstring) {
        //long start = System.currentTimeMillis();
        try {
            int termination = 10; // loop for recursively shortened urls
            while (isApplicable(urlstring) && termination-- > 0) {
                String unshortened = getRedirect(urlstring);
                if (unshortened.equals(urlstring)) return urlstring;
                urlstring = unshortened; // recursive apply unshortener because some unshortener are applied several times
            }
            //DAO.log("UNSHORTENED in " + (System.currentTimeMillis() - start) + " milliseconds: " + urlstring);
            return urlstring;
        } catch (IOException e) {
            return urlstring;
        }
    }

    private static boolean isApplicable(String urlstring) {
        String s = urlstring.toLowerCase();
        if (!s.startsWith("http://") && !s.startsWith("https://")) return false;
        s = s.substring(s.startsWith("https://") ? 8 : 7);
        for (String t: workingHosts) {
            if (s.startsWith(t + "/")) return true;
        }
        for (String t: untestedHosts) { // we just suspect that they work
            if (s.startsWith(t + "/")) return true;
        }
        return false;
    }

    /**
     * this is the raw implementation if ClientConnection.getRedirect.
     * Surprisingly it's much slower, but some redirects cannot be discovered with the other
     * method, but with this one.
     * @param urlstring
     * @return
     * @throws IOException
     */
    private static String getRedirect(String urlstring) throws IOException {
        URL url = new URL(urlstring);
        Socket socket = new Socket(url.getHost(), 80);
        socket.setSoTimeout(2000);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out.println("GET " + url.getPath() + " HTTP/1.1");
        out.println("Host: " + url.getHost());
        // fake a bit that we are real
        out.println("User-Agent: Mozilla/5.0 (Macintosh; U; Intel Mac OS X 10.4; en-US; rv:1.9.2.2) Gecko/20100316 Firefox/3.6.2");
        out.println("Accept-Language: en-us,en;q=0.5");
        out.println("Accept-Encoding: gzip,deflate");
        out.println("Accept-Charset: ISO-8859-1,utf-8;q=0.7,*;q=0.7");
        out.println("Keep-Alive: 300");
        out.println("Connection: keep-alive");
        out.println("Pragma: no-cache");
        out.println("Cache-Control: no-cache");
        out.println(""); // don't forget the empty line at the end
        out.flush();
        // read result
        String line = in.readLine();
        if (line != null && line.contains("301")) {
            // first line should be "HTTP/1.1 301 Moved Permanently"
            // skip most of the next lines, but one should start with "Location:"
            while ((line = in.readLine()) != null) {
                if (line.length() == 0) break;
                if (!line.toLowerCase().startsWith("location:")) continue;
                urlstring = line.substring(9).trim();
                break;
            }
        }
        in.close();
        out.close();
        socket.close();
        return urlstring;
    }

}
