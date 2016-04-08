package io.sponges.bot.modules.polls.cmd;

import io.sponges.bot.api.cmd.Command;
import io.sponges.bot.api.cmd.CommandRequest;
import io.sponges.bot.api.entities.Message;
import io.sponges.bot.api.entities.User;
import io.sponges.bot.api.entities.channel.Channel;
import io.sponges.bot.api.event.events.user.UserChatEvent;
import org.apache.commons.lang3.StringEscapeUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StrawpollCommand extends Command {

    private static final String RESULTS_REQUEST_URL = "https://strawpoll.me/api/v2/polls/";

    private final Map<String, StrawpollBuilder> creating = new ConcurrentHashMap<>();

    public StrawpollCommand() {
        super("create and view the results of strawpoll.me polls", "strawpoll", "strawpoll.me", "strawvote", "spoll");
    }

    @Override
    public void onCommand(CommandRequest request, String[] args) {
        if (args.length == 0) {
            request.reply("Usage:" +
                    "\nstrawpoll view [id] - shows results for the poll (you find the id at the end of the URL)" +
                    "\nstrawpoll create - makes a new poll using the poll builder");
            return;
        }
        Channel channel = request.getChannel();
        User user = request.getUser();
        String userId = user.getId();
        String argument = args[0];
        switch (argument.toLowerCase()) {
            case "view": {
                if (args.length < 2) {
                    request.reply("Invalid command arguments!");
                    break;
                }
                String id = args[1];
                StrawpollResults results;
                try {
                    results = getResults(id);
                } catch (IOException e) {
                    e.printStackTrace();
                    request.reply("Could not get results of the poll \"" + id + "\". Is it a valid poll id? " +
                            "(You find this at the end of the poll link)");
                    return;
                }
                if (results == null) {
                    request.reply("Could not get results of the poll \"" + id + "\". Is it a valid poll id? " +
                            "(You find this at the end of the poll link)");
                    return;
                }
                StringBuilder builder = new StringBuilder();
                builder.append("\"").append(results.title).append("\"");
                for (Map.Entry<String, Integer> entry : results.votes.entrySet()) {
                    builder.append("\n").append(entry.getKey()).append(": ").append(entry.getValue());
                }
                request.reply(builder.toString());
                break;
            }

            case "create": {
                if (creating.containsKey(userId)) {
                    request.reply("You are already in the process of creating a poll! To exit this, say \"exit\". " +
                            "If you are not in the channel you created it in, please navigate to that channel and " +
                            "say \"exit\" in there.");
                    return;
                }
                StrawpollBuilder builder = new StrawpollBuilder(channel);
                creating.put(userId, builder);
                request.reply("Created a new poll. You are now in the poll builder. " +
                        "Say \"exit\" to cancel the poll creation at any time." +
                        "\n>> What should the topic of the poll (the title) be?");
                break;
            }

            default: {
                request.reply("Invalid command arguments!");
            }
        }
    }

    public void onUserChat(UserChatEvent event) {
        Channel channel = event.getChannel();
        User user = event.getUser();
        String userId = user.getId();
        Message message = event.getMessage();
        String content = message.getContent();
        if (!creating.containsKey(userId)) {
            return;
        }
        StrawpollBuilder builder = creating.get(userId);
        if (!builder.getChannel().getId().equals(channel.getId())) return;
        if (content.equalsIgnoreCase("exit")) {
            creating.remove(userId);
            channel.sendChatMessage("Exited the poll creation! Create another one any time with 'strawpoll create'.");
            return;
        }
        StrawpollBuilder.SetupStage stage = builder.getSetupStage();
        switch (stage) {
            case TITLE: {
                builder.setTitle(content);
                channel.sendChatMessage("Set the poll topic to \"" + content + "\". " +
                        "Say \"exit\" to cancel the poll creation at any time." +
                        "\n>> What should the poll answers be? Say each answer in a new message." +
                        "\n>> Once you have finished, say \"done\".");
                break;
            }

            case OPTIONS: {
                if (content.equalsIgnoreCase("done")) {
                    if (builder.getNumberOfOptions() < 2) {
                        channel.sendChatMessage("You need to set at least 2 poll answers! " +
                                "Say \"exit\" to cancel the poll creation at any time." +
                                "\n>> To add an answer, send a new message." +
                                "\n>> Once you have finished, say \"done\".");
                        return;
                    }
                    builder.finishOptions();
                    channel.sendChatMessage("Set the poll options! " +
                            "Say \"exit\" to cancel the poll creation at any time." +
                            "\n>> Should voters be allowed to vote on multiple answers? (yes/no)");
                    break;
                }
                builder.addOption(content);
                channel.sendChatMessage("Added \"" + content + "\" as an answer to the poll. " +
                        "Say \"exit\" to cancel the poll creation at any time." +
                        "\n>> To add another answer, send a new message." +
                        "\n>> Once you have finished, say \"done\".");
                break;
            }

            case MULTI: {
                String lowerCase = content.toLowerCase();
                boolean allowed;
                if (lowerCase.equals("true") || lowerCase.equals("yes")) {
                    allowed = true;
                } else if (lowerCase.equals("false") || lowerCase.equals("no")) {
                    allowed = false;
                } else {
                    channel.sendChatMessage("Invalid input \"" + content +"\"! " +
                            "Say \"exit\" to cancel the poll creation at any time." +
                            "\n>> Should voters be allowed to vote on multiple answers? (yes/no)");
                    return;
                }
                builder.setIsMulti(allowed);
                channel.sendChatMessage("Multi-votes allowed? \"" + allowed + "\" " +
                        "Say \"exit\" to cancel the poll creation at any time." +
                        "\n>> Should voters have to fill in a captcha before voting? (yes/no)" +
                        "\nThis prevents the poll from being 'botted' and uses Google's 'one click' captcha system");
                break;
            }

            case CAPTCHA: {
                String lowerCase = content.toLowerCase();
                boolean captcha;
                if (lowerCase.equals("true") || lowerCase.equals("yes")) {
                    captcha = true;
                } else if (lowerCase.equals("false") || lowerCase.equals("no")) {
                    captcha = false;
                } else {
                    channel.sendChatMessage("Invalid input \"" + content +"\"! " +
                            "Say \"exit\" to cancel the poll creation at any time." +
                            "\n>> Should voters have to fill in a captcha before voting? (yes/no)");
                    return;
                }
                builder.setIsCaptcha(captcha);
                creating.remove(userId);
                Strawpoll strawpoll;
                try {
                    strawpoll = builder.create();
                } catch (IOException e) {
                    e.printStackTrace();
                    channel.sendChatMessage("Could not create the poll! " + e.getMessage());
                    return;
                }
                if (strawpoll.getId() == -1) {
                    channel.sendChatMessage("Could not create the poll! Make sure you setup the poll correctly.");
                    return;
                }
                channel.sendChatMessage("Poll created with id \"" + strawpoll.getId() + "\": " + strawpoll.getUrl());
                break;
            }
        }
    }

    private StrawpollResults getResults(String id) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(RESULTS_REQUEST_URL + id).openConnection();
        connection.addRequestProperty("User-Agent", "Mozilla/5.0");
        String response;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            response = reader.readLine();
        }
        JSONObject json = new JSONObject(response);
        if (!json.isNull("error")) {
            return null;
        }
        String title = json.getString("title");
        boolean multi = json.getBoolean("multi");
        JSONArray keysArray = json.getJSONArray("options");
        String[] keys = new String[keysArray.length()];
        for (int i = 0; i < keysArray.length(); i++) {
            keys[i] = keysArray.getString(i);
        }
        JSONArray valuesArray = json.getJSONArray("votes");
        int[] values = new int[keysArray.length()];
        for (int i = 0; i < keysArray.length(); i++) {
            values[i] = valuesArray.getInt(i);
        }
        return new StrawpollResults(id, title, multi, keys, values);
    }

    private class StrawpollResults {

        private final String id;
        private final String title;
        private final boolean multi;
        private final Map<String, Integer> votes;

        public StrawpollResults(String id, String title, boolean multi, String[] keys, int[] values) {
            this.id = id;
            this.title = title;
            this.multi = multi;
            this.votes = new HashMap<>();
            for (int i = 0; i < keys.length; i++) {
                this.votes.put(keys[i], values[i]);
            }
        }
    }

} class Strawpoll {

    private int id = -1;

    public Strawpoll(String title, List<String> options, boolean multi, boolean captcha, String duplicateCheck) throws IOException {
        JSONObject json = new JSONObject()
                .put("title", StringEscapeUtils.unescapeJson(title))
                .put("options", options)
                .put("multi", multi)
                .put("captcha", captcha)
                .put("dupcheck", duplicateCheck);

        HttpURLConnection con;
        con = (HttpURLConnection) new URL("http://strawpoll.me/api/v2/polls").openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("User-Agent", "Mozilla/5.0");
        con.setRequestProperty("Content-Type", "application/json");
        con.setDoOutput(true);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(con.getOutputStream()))) {
            writer.write(json.toString());
        }
        int code = con.getResponseCode();
        if (code != 201) {
            System.out.println("Creating poll returned response code " + code + " instead of expected 201?");
            return;
        }
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            String input;
            while ((input = reader.readLine()) != null) {
                response.append(input).append("\n");
            }
        }

        this.id = new JSONObject(response.toString()).getInt("id");
    }

    public int getId() {
        return id;
    }

    public String getUrl() {
        return "http://strawpoll.me/" + id;
    }

} class StrawpollBuilder {

    private String title;
    private List<String> options = new ArrayList<>();
    private boolean isMulti;
    private boolean isCaptcha;

    private SetupStage setupStage;

    private final Channel channel;

    StrawpollBuilder(Channel channel) {
        this.channel = channel;
        this.setupStage = SetupStage.TITLE;
    }

    public Strawpoll create() throws IOException {
        return new Strawpoll(title, options, isMulti, isCaptcha, "normal");
    }

    public void setTitle(String title) {
        this.title = title;
        this.setupStage = SetupStage.OPTIONS;
    }

    public void addOption(String option) {
        this.options.add(option);
    }

    public int getNumberOfOptions() {
        return options.size();
    }

    public void finishOptions() {
        this.setupStage = SetupStage.MULTI;
    }

    public void setIsMulti(boolean isMulti) {
        this.isMulti = isMulti;
        this.setupStage = SetupStage.CAPTCHA;
    }

    public void setIsCaptcha(boolean isCaptcha) {
        this.isCaptcha = isCaptcha;
        this.setupStage = SetupStage.DONE;
    }

    public SetupStage getSetupStage() {
        return setupStage;
    }

    public Channel getChannel() {
        return channel;
    }

    public enum SetupStage {

        TITLE, OPTIONS, MULTI, CAPTCHA, DONE

    }

}
