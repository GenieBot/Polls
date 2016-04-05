package io.sponges.bot.modules.polls;

import io.sponges.bot.api.cmd.CommandManager;
import io.sponges.bot.api.event.events.user.UserChatEvent;
import io.sponges.bot.api.event.framework.EventManager;
import io.sponges.bot.api.module.Module;
import io.sponges.bot.modules.polls.cmd.StrawpollCommand;

public class Polls extends Module {

    public Polls() {
        super("Polls", "1.02");
    }

    @Override
    public void onEnable() {
        CommandManager commandManager = getCommandManager();
        EventManager eventManager = getEventManager();

        StrawpollCommand strawpollCommand = new StrawpollCommand();
        commandManager.registerCommand(this, strawpollCommand);
        eventManager.register(this, UserChatEvent.class, strawpollCommand::onUserChat);
    }

    @Override
    public void onDisable() {

    }
}
