/**
 *  TwitterScraper
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

import org.loklak.harvester.TwitterScraper.TwitterTweet;
import org.loklak.objects.MessageEntry;
import org.loklak.objects.Timeline;
import org.loklak.objects.UserEntry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

public class TwitterScraper {

    public final static ExecutorService executor = Executors.newFixedThreadPool(40);
    
    public static Timeline search(String query, final Timeline.Order order) {
        String https_url = "";

        try {https_url = "https://twitter.com/search?f=tweets&vertical=default&q=" + URLEncoder.encode(query, "UTF-8") + "&src=typd";} catch (UnsupportedEncodingException e) {}
        Timeline timeline = null;
        try {
            URL url = new URL(https_url);
            HttpsURLConnection con = (HttpsURLConnection)url.openConnection();
            con.setReadTimeout(10000 /* milliseconds */);
            con.setConnectTimeout(15000 /* milliseconds */);
            con.setRequestMethod("GET");
            con.setDoInput(true);
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; U; Intel Mac OS X 10.4; en-US; rv:1.9.2.2) Gecko/20100316 Firefox/3.6.2");

            BufferedReader br = null;
            try {
               br = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"));
               timeline = search(br, order);
            } catch (IOException e) {
               e.printStackTrace();
            } finally {
                try {
                    if (br != null) br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        };
        return timeline;
    }

    private static Timeline parse(
            final File file,
            final Timeline.Order order) {
        Timeline timeline = null;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            timeline = search(br, order);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (timeline == null) timeline = new Timeline(order);
        }

        if (timeline != null) timeline.setScraperInfo("local");
        return timeline;
    }
    
    public static Timeline search(
            final BufferedReader br,
            final Timeline.Order order) throws IOException {
        Timeline timelineReady = new Timeline(order);
        String input;
        Map<String, prop> props = new HashMap<String, prop>();
        Set<String> images = new LinkedHashSet<String>();
        Set<String> videos = new LinkedHashSet<String>();
        String place_id = "", place_name = "";
        boolean parsing_favourite = false, parsing_retweet = false;
        while ((input = br.readLine()) != null){
            input = input.trim();
            //System.out.println(input); // uncomment temporary to debug or add new fields
            int p;
            if ((p = input.indexOf("class=\"account-group")) > 0) {
                props.put("userid", new prop(input, p, "data-user-id"));
                continue;
            }
            if ((p = input.indexOf("class=\"avatar")) > 0) {
                props.put("useravatarurl", new prop(input, p, "src"));
                continue;
            }
            if ((p = input.indexOf("class=\"fullname")) > 0) {
                props.put("userfullname", new prop(input, p, null));
                continue;
            }
            if ((p = input.indexOf("class=\"username")) > 0) {
                props.put("usernickname", new prop(input, p, null));
                continue;
            }
            if ((p = input.indexOf("class=\"tweet-timestamp")) > 0) {
                props.put("tweetstatusurl", new prop(input, 0, "href"));
                props.put("tweettimename", new prop(input, p, "title"));
                // don't continue here because "class=\"_timestamp" is in the same line
            }
            if ((p = input.indexOf("class=\"_timestamp")) > 0) {
                props.put("tweettimems", new prop(input, p, "data-time-ms"));
                continue;
            }
            if ((p = input.indexOf("class=\"ProfileTweet-action--retweet")) > 0) {
                parsing_retweet = true;
                continue;
            }
            if ((p = input.indexOf("class=\"ProfileTweet-action--favorite")) > 0) {
                parsing_favourite = true;
                continue;
            }
            if ((p = input.indexOf("class=\"TweetTextSize")) > 0) {
                props.put("tweettext", new prop(input, p, null));
                continue;
            }
            if ((p = input.indexOf("class=\"ProfileTweet-actionCount")) > 0) {
                if (parsing_retweet) {
                    props.put("tweetretweetcount", new prop(input, p, "data-tweet-stat-count"));
                    parsing_retweet = false;
                }
                if (parsing_favourite) {
                    props.put("tweetfavouritecount", new prop(input, p, "data-tweet-stat-count"));
                    parsing_favourite = false;
                }
                continue;
            }
            // get images
            if ((p = input.indexOf("class=\"media media-thumbnail twitter-timeline-link media-forward is-preview")) > 0 ||
                    (p = input.indexOf("class=\"multi-photo")) > 0) {
                images.add(new prop(input, p, "data-resolved-url-large").value);
                continue;
            }
            // we have two opportunities to get video thumbnails == more images; images in the presence of video content should be treated as thumbnail for the video
            if ((p = input.indexOf("class=\"animated-gif-thumbnail\"")) > 0) {
                images.add(new prop(input, 0, "src").value);
                continue;
            }
            if ((p = input.indexOf("class=\"animated-gif\"")) > 0) {
                images.add(new prop(input, p, "poster").value);
                continue;
            }
            if ((p = input.indexOf("<source video-src")) >= 0 && input.indexOf("type=\"video/") > p) {
                videos.add(new prop(input, p, "video-src").value);
                continue;
            }
            if ((p = input.indexOf("class=\"Tweet-geo")) > 0) {
                prop place_name_prop = new prop(input, p, "title");
                place_name = place_name_prop.value;
                continue;
            }
            if ((p = input.indexOf("class=\"ProfileTweet-actionButton u-linkClean js-nav js-geo-pivot-link")) > 0) {
                prop place_id_prop = new prop(input, p, "data-place-id");
                place_id = place_id_prop.value;
                continue;
            }
            if (props.size() == 10) {
                // the tweet is complete, evaluate the result
                UserEntry user = new UserEntry(
                        props.get("userid").value,
                        props.get("usernickname").value,
                        props.get("useravatarurl").value,
                        MessageEntry.html2utf8(props.get("userfullname").value)
                );
                ArrayList<String> imgs = new ArrayList<String>(images.size()); imgs.addAll(images);
                ArrayList<String> vids = new ArrayList<String>(videos.size()); vids.addAll(videos);
                TwitterTweet tweet = new TwitterTweet(
                        user.getScreenName(),
                        Long.parseLong(props.get("tweettimems").value),
                        props.get("tweettimename").value,
                        props.get("tweetstatusurl").value,
                        props.get("tweettext").value,
                        Long.parseLong(props.get("tweetretweetcount").value),
                        Long.parseLong(props.get("tweetfavouritecount").value),
                        imgs, vids, place_name, place_id,
                        user
                );

                tweet.run();
                timelineReady.add(tweet, user);

                images.clear();
                props.clear();
                continue;
            }
        }
        //for (prop p: props.values()) System.out.println(p);
        br.close();
        return timelineReady;
    }

    private static class prop {
        public String key, value = null;
        public prop(String line, int start, String key) {
            this.key = key;
            if (key == null) {
                int p = line.indexOf('>', start);
                if (p > 0) {
                    int c = 1;
                    int q = p + 1;
                    while (c > 0 && q < line.length()) {
                        char a = line.charAt(q);
                        if (a == '<') {
                            if (line.charAt(q+1) != 'i') {
                                if (line.charAt(q+1) == '/') c--; else c++;
                            }
                        }
                        q++;
                    }
                    value = line.substring(p + 1, q - 1);
                }
            } else {
                int p  = line.indexOf(key + "=\"", start);
                if (p > 0) {
                    int q = line.indexOf('"', p + key.length() + 2);
                    if (q > 0) {
                        value = line.substring(p + key.length() + 2, q);
                    }
                }
            }
        }

        @SuppressWarnings("unused")
        public boolean success() {
            return value != null;
        }

        public String toString() {
            return this.key + "=" + (this.value == null ? "unknown" : this.value);
        }
    }

    final static Pattern hashtag_pattern = Pattern.compile("<a href=\"/hashtag/.*?\".*?class=\"twitter-hashtag.*?\".*?><s>#</s><b>(.*?)</b></a>");
    final static Pattern timeline_link_pattern = Pattern.compile("<a href=\"https://(.*?)\".*? data-expanded-url=\"(.*?)\".*?twitter-timeline-link.*?title=\"(.*?)\".*?>.*?</a>");
    final static Pattern timeline_embed_pattern = Pattern.compile("<a href=\"(https://t.co/\\w+)\" class=\"twitter-timeline-link.*?>pic.twitter.com/(.*?)</a>");
    final static Pattern emoji_pattern = Pattern.compile("<img .*?class=\"Emoji Emoji--forText\".*?alt=\"(.*?)\".*?>");
    final static Pattern doublespace_pattern = Pattern.compile("  ");
    final static Pattern cleanup_pattern = Pattern.compile(
        "</?(s|b|strong)>|" +
        "<a href=\"/hashtag.*?>|" +
        "<a.*?class=\"twitter-atreply.*?>|" +
        "<span.*?span>"
    );

    public static class TwitterTweet extends MessageEntry implements Runnable {

        private final Semaphore ready;
        private Boolean exists = null;
        private UserEntry user;

        public TwitterTweet(
                final String user_screen_name_raw,
                final long created_at_raw,
                final String created_at_name_raw, // not used here but should be compared to created_at_raw
                final String status_id_url_raw,
                final String text_raw,
                final long retweets,
                final long favourites,
                final Collection<String> images,
                final Collection<String> videos,
                final String place_name,
                final String place_id,
                final UserEntry user) throws MalformedURLException {
            super();
            this.screen_name = user_screen_name_raw;
            this.created_at = new Date(created_at_raw);
            this.status_id_url = new URL("https://twitter.com" + status_id_url_raw);
            int p = status_id_url_raw.lastIndexOf('/');
            this.id_str = p >= 0 ? status_id_url_raw.substring(p + 1) : "-1";
            this.retweet_count = retweets;
            this.favourites_count = favourites;
            this.place_name = place_name;
            this.place_id = place_id;
            this.images = new LinkedHashSet<String>(); for (String image: images) this.images.add(image);
            this.videos = new LinkedHashSet<String>(); for (String video: videos) this.videos.add(video);
            this.text = text_raw;
            this.user = user;
            //Date d = new Date(timemsraw);
            //System.out.println(d);

            /* failed to reverse-engineering the place_id :(
            if (place_id.length() == 16) {
                String a = place_id.substring(0, 8);
                String b = place_id.substring(8, 16);
                long an = Long.parseLong(a, 16);
                long bn = Long.parseLong(b, 16);
                System.out.println("place = " + place_name + ", a = " + an + ", b = " + bn);
                // Frankfurt a = 3314145750, b = 3979907708, http://www.openstreetmap.org/#map=15/50.1128/8.6835
                // Singapore a = 1487192992, b = 3578663936
            }
            */

            // this.text MUST be analysed with analyse(); this is not done here because it should be started concurrently; run run();

            this.ready = new Semaphore(0);
        }

        public UserEntry getUser() {
            return this.user;
        }

        public boolean willBeTimeConsuming() {
            return timeline_link_pattern.matcher(this.text).find() || timeline_embed_pattern.matcher(this.text).find();
        }

        @Override
        public void run() {
            //long start = System.currentTimeMillis();
            try {
                //DAO.log("TwitterTweet [" + this.id_str + "] start");
                this.text = unshorten(this.text);
                //DAO.log("TwitterTweet [" + this.id_str + "] unshorten after " + (System.currentTimeMillis() - start) + "ms");
                this.enrich();
                //DAO.log("TwitterTweet [" + this.id_str + "] enrich    after " + (System.currentTimeMillis() - start) + "ms");
            } catch (Throwable e) {
                e.printStackTrace();
            } finally {
                this.ready.release(1000);
            }
        }

        public boolean isReady() {
            if (this.ready == null) throw new RuntimeException("isReady() should not be called if postprocessing is not started");
            return this.ready.availablePermits() > 0;
        }

        public boolean waitReady(long millis) {
            if (this.ready == null)
                throw new RuntimeException("waitReady() should not be called if postprocessing is not started");
            if (this.ready.availablePermits() > 0) return true;
            try {
                return this.ready.tryAcquire(millis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }
    }

    public static String unshorten(String text) {
        while (true) {
            try {
                Matcher m = timeline_link_pattern.matcher(text);
                if (m.find()) {
                    String expanded = RedirectUnshortener.unShorten(m.group(2));
                    text = m.replaceFirst(expanded);
                    continue;
                }
            } catch (Throwable e) {
                e.printStackTrace();
                break;
            }
            try {
                Matcher m = timeline_embed_pattern.matcher(text);
                if (m.find()) {
                    String shorturl = RedirectUnshortener.unShorten(m.group(2));
                    text = m.replaceFirst("https://pic.twitter.com/" + shorturl + " ");
                    continue;
                }
            } catch (Throwable e) {
                e.printStackTrace();
                break;
            }
            try {
                Matcher m = emoji_pattern.matcher(text);
                if (m.find()) {
                    String emoji = m.group(1);
                    text = m.replaceFirst(emoji);
                    continue;
                }
            } catch (Throwable e) {
                e.printStackTrace();
                break;
            }
            break;
        }
        text = cleanup_pattern.matcher(text).replaceAll("");
        text = MessageEntry.html2utf8(text);
        text = doublespace_pattern.matcher(text).replaceAll(" ");
        text = text.trim();
        return text;
    }

   /**
    * Usage: java twitter4j.examples.search.SearchTweets [query]
    *
    * @param args search query
    */
   public static void main(String[] args) {
       //wget --no-check-certificate "https://twitter.com/search?q=eifel&src=typd&f=realtime"
       
       Timeline result = null;
       if (args[0].startsWith("/"))
           result = parse(new File(args[0]),Timeline.Order.CREATED_AT);
       else
           result = TwitterScraper.search(args[0], Timeline.Order.CREATED_AT);
       for (MessageEntry tweet : result) {
           if (tweet instanceof TwitterTweet) {
               ((TwitterTweet) tweet).waitReady(10000);
           }
           System.out.println(tweet.getCreatedAt().toString() + " from @" + tweet.getScreenName() + " - " + tweet.getText(Integer.MAX_VALUE, ""));
       }
       System.out.println("count: " + result.size());
       System.exit(0);
   }

}


