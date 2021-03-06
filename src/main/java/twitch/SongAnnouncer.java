package twitch;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import database.BobsDatabase;
import database.BobsDatabaseHelper;
import database.SongDatabase;
import utility.GBUtility;
import javafx.util.Pair;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.MessageEvent;

import javax.sql.rowset.CachedRowSet;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

//TODO: Account for stream delay of ~16seconds
public class SongAnnouncer extends ListenerAdapter {
    private static final HashMap<String, String> ratingReminderMap = new HashMap<>();
    private static final HashMap<String, String> quoteReminderMap = new HashMap<>();
    private static final int STREAMDELAYINSECONDS = 10;
    private static final Random random = new Random();
    private static String currentSong = "Guardsman Bob";
    private static String displayOnStreamSong = "Guardsman Bob";
    private static float displayOnStreamSongRating = 0f;
    private static int displayOnStreamNumberOfRatings = 0;

    public SongAnnouncer(Path songFilePath) {
        startSongAnnouncer();
        watchSongFile(songFilePath);
        //Load the rating reminders
        try (CachedRowSet cachedRowSet = BobsDatabase.getCachedRowSetFromSQL("SELECT twitchUserID, twitchDisplayName FROM TwitchChatUsers WHERE songRatingReminder = true")) {
            while (cachedRowSet.next()) ratingReminderMap.put(cachedRowSet.getString("twitchUserID"), cachedRowSet.getString("twitchDisplayName"));
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onMessage(MessageEvent event) {
        TwitchChatMessage tcm = new TwitchChatMessage(event);
        if (tcm.message.toLowerCase().startsWith("!rate ")) {
            String songQuote = "none";
            try {
                int rating = Integer.parseInt(tcm.getMessageContent().split(" ")[0]);
                if (rating < 1 ) rating = 1;
                if (rating > 11) rating = 11;
                if (tcm.getMessageContent().contains(" ")) songQuote = tcm.getMessageContent().substring(tcm.getMessageContent().indexOf(" ")).trim();

                SongDatabase.addSongRating(tcm.userID, currentSong, rating, songQuote);
                Pair<Float, Integer> songRatingPair = getSongRating(displayOnStreamSong);
                displayOnStreamSongRating = songRatingPair.getKey();
            } catch (NumberFormatException nfe) {
                // Silently kill number format exceptions
            }
        } else {
            switch (tcm.getMessageCommand()) {
                case "!ratereminder": ratingReminderMap.put(tcm.userID, tcm.displayName); BobsDatabaseHelper.setSongRatingReminder(tcm.userID, tcm.displayName, true); break;
                case "!removeratereminder": ratingReminderMap.remove(tcm.userID); BobsDatabaseHelper.setSongRatingReminder(tcm.userID, tcm.displayName,false); break;
            }
        }
    }

    public static void startSongAnnouncer() {
        try {
            HttpServer server = HttpServer.create( new InetSocketAddress(9100), 0);
            server.createContext("/songs", new songOverlayHttpHandler());
            server.createContext("/songPlaying", new songHttpHandler());
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Pair<Float, Integer> getSongRating(String songName) {
        CachedRowSet songRatingSet = BobsDatabase.getCachedRowSetFromSQL("SELECT songRating FROM SongRatings WHERE songName = ?", songName);
        int numberOfRatings = 0;
        int totalRating = 0;
        try {
            while (songRatingSet.next()) {
                totalRating += songRatingSet.getInt("songRating");
                numberOfRatings++;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if (numberOfRatings == 0) return new Pair<>(0f, 0);
        return new Pair<>((float) totalRating/numberOfRatings, numberOfRatings);
    }

    /**
     * Select all song quotes and pick a random one, half the time we want to guarentee that we pick a quote from someone in chat
     * @return a random song quote
     */
    private static String getSongQuote(String songName, boolean nameFirst) {
        List<String> quotesFromEveryone = new ArrayList<>();
        List<String> quotesFromChat = new ArrayList<>();

        Set<String> lowerCasePeopleInChat = TwitchChat.getLowerCaseNamesInChannel("#guardsmanbob");

        try (CachedRowSet cachedRowSet = BobsDatabase.getCachedRowSetFromSQL("SELECT TwitchChatUsers.twitchDisplayName, songQuote FROM SongRatings INNER JOIN TwitchChatUsers ON TwitchChatUsers.TwitchUserID = SongRatings.twitchUserID WHERE songName = ? AND songQuote <> 'none'", songName)) {
            while (cachedRowSet.next()) {
                String quote = cachedRowSet.getString("songQuote");
                String name = cachedRowSet.getString("twitchDisplayName");
                String nameQuote = (nameFirst) ? name + ": " + quote : quote + " - " + name;
                if (lowerCasePeopleInChat.contains(name.toLowerCase())) quotesFromChat.add(nameQuote);
                else quotesFromEveryone.add(nameQuote);
            }
            if (quotesFromChat.size() > 0 && random.nextBoolean()) return quotesFromChat.get(random.nextInt(quotesFromChat.size()));
            if (quotesFromEveryone.size() > 0) return quotesFromEveryone.get(random.nextInt(quotesFromEveryone.size()));
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "";
    }

    private static void songFileChange(String newSongName) {
        if (newSongName.equalsIgnoreCase("Guardsman Bob")) return;

        String lastSongString = displayOnStreamSong + " ⏩ Rating: " + String.format("%.2f", displayOnStreamSongRating) + " [" + displayOnStreamNumberOfRatings + "]";

        Pair<Float, Integer> songRatingPair = getSongRating(newSongName);
        float newSongRating = songRatingPair.getKey();
        displayOnStreamSong = newSongName;
        displayOnStreamSongRating = newSongRating;
        displayOnStreamNumberOfRatings = songRatingPair.getValue();
        System.out.println("New Song: " + newSongName + " .. Song quote: " + getSongQuote(newSongName, true));

        if (newSongRating < 7.7f) GBUtility.textToBob("Do you want to remove the song: " + newSongName + " ⏩ rating: " +newSongRating);

        new Thread(() -> {
            try { Thread.sleep(1000 * STREAMDELAYINSECONDS); } catch (InterruptedException e) { e.printStackTrace(); }
            //Check if the song to be announced in chat is actually still playing
            if (displayOnStreamSong.equalsIgnoreCase(newSongName)) {
                currentSong = newSongName;
                TwitchChat.sendMessage("\uD83C\uDFB8\uD83C\uDFBB Now Playing: " + newSongName + " \uD83D\uDD37\uD83D\uDD37 Last Song: " + lastSongString);

                //Add long delay before song rating reminder, so allow for people to rate the song and not be reminded.
                try { Thread.sleep(25000); } catch (InterruptedException e) { e.printStackTrace(); }
                Set<String> peopleInChat = TwitchChat.getActiveUserIDsInChannel(Duration.ofMinutes(90));
                String remindString = ratingReminderMap.keySet().stream()
                        .filter(peopleInChat::contains)
                        .filter(twitchID -> getIndividualSongRating(twitchID, newSongName) == 0)
                        .limit(20)
                        .map(ratingReminderMap::get)
                        .collect(Collectors.joining(", "));
                if (!remindString.isEmpty()) TwitchChat.sendMessage("Rate The Song! -> " + remindString);

                //Send out random songquote
                try { Thread.sleep(10000); } catch (InterruptedException e) { e.printStackTrace(); }
                TwitchChat.sendMessage(getSongQuote(newSongName, false));
            } else {
                System.out.println("ignoring " + newSongName + "Another song is already playing");
            }
        }).start();
        SongDatabase.addSongRating("39837384", newSongName, 11, "none" );
    }

    private static int getIndividualSongRating(String twitchUserID, String songName) {
        int returnRating = 0;

        try (CachedRowSet cachedRowSet = BobsDatabase.getCachedRowSetFromSQL("SELECT songRating FROM SongRatings WHERE twitchUserID = ? AND songName = ?", twitchUserID, songName)) {
            while (cachedRowSet.next()) returnRating = cachedRowSet.getInt("songRating");
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return returnRating;
    }

    private static void watchSongFile(Path songFileLocation) {
        new Thread(() -> {
            try {
            WatchService fileWatcher = FileSystems.getDefault().newWatchService();
            songFileLocation.getParent().register(fileWatcher, StandardWatchEventKinds.ENTRY_MODIFY);
            String lastSongNameInFile = "none";
            while (true) {
                WatchKey key = fileWatcher.take();
                for (WatchEvent event : key.pollEvents()) {
                    Path eventFilePath = (Path) event.context();
                    if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY && eventFilePath.endsWith(songFileLocation.getFileName())) {
                        List<String> songFileLineArray = Files.readAllLines(songFileLocation, Charset.forName("windows-1252"));
                        //if for some reason the file is empty just ignore it.
                        if (songFileLineArray.size() == 0) break;
                        String newSongNameInFile = songFileLineArray.get(0);
                        if (!lastSongNameInFile.equalsIgnoreCase(newSongNameInFile)) {
                            songFileChange(newSongNameInFile);
                            lastSongNameInFile = newSongNameInFile;
                        }
                    }
                }
                if (key.reset() == false) {
                    System.out.println("Song file Watching went horrible wrong!");
                    break;
                }
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        } ).start();
    }


    private static class songHttpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            ObjectNode rootNode = JsonNodeFactory.instance.objectNode();
            rootNode.put("songName", displayOnStreamSong);
            rootNode.put("songRating", String.format("%.2f", displayOnStreamSongRating));
            rootNode.put("numberOfRatings", displayOnStreamNumberOfRatings);
            rootNode.put("songQuote", getSongQuote(displayOnStreamSong, true));

            String response = rootNode.toString();
            exchange.sendResponseHeaders(200, response.getBytes().length);
            exchange.getResponseBody().write(response.getBytes());
            exchange.getResponseBody().close();
            exchange.close();
        }
    }

    private static class songOverlayHttpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println(exchange.getRequestURI().toString());
            String response = "<html>" +
                    "<head>" +
                    "   <style>" +
                    "       body { font: 26px \"Helvetica Neue\",Helvetica,Arial,sans-serif; color: #eeeeee; " +
                    "           font-weight: 700;" +
                    "           text-shadow: 0px 0px 20px #000000, 0px 0px 15px #000000, 0px 0px 15px #000000, 0px 0px 15px #000000, 0px 0px 15px #000000; " +
                    "       }" +
                    "   </style>" +
                    "<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/animate.css/3.5.2/animate.min.css\">" +
                    "<script src=\"https://ajax.googleapis.com/ajax/libs/jquery/3.1.1/jquery.min.js\"></script>" +
                    "<script src=\"http://jschr.github.io/textillate/jquery.textillate.js\"></script>" +
                    getLetteringJS()+
                    "</head>" +
                    "<body>" +
                    "   <div id =\"song\" class=\"tlt\" style=\"display: inline-block; visibility: hidden; \">Bobs Song Rating!</div>" +
                    "   <div class=\"tlt2\" style=\"display: inline-block; color: #ff9900; visibility: hidden;\">10,00</div>" +
                    "   <div class=\"tlt3\" style=\"display: inline-block; color: #dddddd; font-weight: 300; font-size: 20px; vertical-align: middle; visibility: hidden;\"> [150]</div>" +
                    "<script>" +
                    "   var currentSongPlaying = 'Guardsman';" +
                    "   var currentSongRating = '9,50';" +
                    "   var currentSongQuote = 'Guardsman Bob Is Awesome';" +
                    "   var numberOfRatings = 42;" +
                    "   var timeToNextUpdate = 2000;" +
                    "   function runUpdates() {" +
                    "       var request = new XMLHttpRequest();" +
                    "       request.onreadystatechange = function() {" +
                    "           if (request.readyState === XMLHttpRequest.DONE && request.status === 200) {" +
                    "               var songJSON = JSON.parse(request.responseText);" +
                    "               updateSong(songJSON);" +
                    "           }" +
                    "       };" +
                    "       request.open('GET', 'http://127.0.0.1:9100/songPlaying', true);" +
                    "       request.setRequestHeader(\"Content-Type\", \"text/plain\");" +
                    "       request.send();" +
                    "       setTimeout(runUpdates, timeToNextUpdate);" +
                    "   }" +
                    "   function updateSong(newSongJSON) {" +
                    "       if (currentSongPlaying === newSongJSON.songName) {" +
                    "           if (currentSongRating === newSongJSON.songRating) {" +
                    "               timeToNextUpdate = 2000;" +
                    "           } else {" +
                    "               timeToNextUpdate = 6000;" +
                    "               currentSongRating = newSongJSON.songRating;" +
                    "               songRatingColor = newSongJSON.songRatingColor;" +
                    "               $('.tlt2').textillate('out');" +
                    "               setTimeout(songRatingIn, 1500)" +
                    "           }" +
                    "       } else {" +
                    "           timeToNextUpdate = 8000;" +
                    "           currentSongPlaying = newSongJSON.songName;" +
                    "           currentSongRating = newSongJSON.songRating;" +
                    "           currentSongQuote = newSongJSON.songQuote;" +
                    "           numberOfRatings = newSongJSON.numberOfRatings;" +
                    "           $('.tlt').textillate('out');" +
                    "           $('.tlt2').textillate('out');" +
                    "       }" +
                    "   }" +
                    "   function setNewSong() {" +
                    "       $('.tlt').find('li').html(currentSongPlaying);" +
                    "       $('.tlt3').find('li').html(' Vote Count: ' + numberOfRatings);" +
                    "       $('.tlt').textillate('start');" +
                    "       $('.tlt3').textillate('start');" +
                    "       setTimeout(numberOfRatingsOut, 11000);" +
                    "   }" +
                    "   function songRatingIn() {" +
                    "       $('.tlt2').find('li').html(currentSongRating);" +
                    "       $('.tlt2').css('color','hsl('+ ((currentSongRating.replace(',','.') - 1)*24-120) +',100%,50%)');" +
                    "       $('.tlt2').textillate('in');" +
                    "   }" +
                    "   function numberOfRatingsOut() { $('.tlt3').textillate('out'); }" +
                    "$('.tlt').textillate({initialDelay: 1500, in: { effect: 'bounceIn', callback: songRatingIn }, out: { effect: 'bounceOut', sync: true, callback: setNewSong }, type: 'word' });" +
                    "$('.tlt2').textillate({ autoStart: false, in: { effect: 'fadeIn' }, out: { effect: 'hinge', sync: true }, type: 'word' });" +
                    "$('.tlt3').textillate({ initialDelay: 4000, autoStart: false, in: { effect: 'fadeInRight', sync: true }, out: { effect: 'fadeOut' }, type: 'word' });" +
                    "setTimeout(runUpdates, 5000);" +
                    "</script>" +
                    "</body>" +
                    "</html>";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            exchange.getResponseBody().write(response.getBytes());
            exchange.getResponseBody().close();
            exchange.close();
        }
    }


    private static String getLetteringJS() {
        return "<script>(function($){function injector(t,splitter,klass,after){var a=t.text().split(splitter),inject='';if(a.length){$(a).each(function(i,item){inject+='<span class=\"'+klass+(i+1)+'\">'+item+'</span>'+after});t.empty().append(inject)}}var methods={init:function(){return this.each(function(){injector($(this),'','char','')})},words:function(){return this.each(function(){injector($(this),' ','word',' ')})},lines:function(){return this.each(function(){var r=\"eefec303079ad17405c889e092e105b0\";injector($(this).children(\"br\").replaceWith(r).end(),r,'line','')})}};$.fn.lettering=function(method){if(method&&methods[method]){return methods[method].apply(this,[].slice.call(arguments,1))}else if(method==='letters'||!method){return methods.init.apply(this,[].slice.call(arguments,0))}$.error('Method '+method+' does not exist on jQuery.lettering');return this}})(jQuery);</script>";
    }
}
