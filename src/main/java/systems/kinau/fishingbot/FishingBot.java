/*
 * Created by David Luedtke (MrKinau)
 * 2019/5/3
 */

package systems.kinau.fishingbot;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.cli.CommandLine;
import systems.kinau.fishingbot.auth.AuthData;
import systems.kinau.fishingbot.auth.Authenticator;
import systems.kinau.fishingbot.bot.Player;
import systems.kinau.fishingbot.command.CommandRegistry;
import systems.kinau.fishingbot.command.commands.*;
import systems.kinau.fishingbot.event.EventManager;
import systems.kinau.fishingbot.fishing.ItemHandler;
import systems.kinau.fishingbot.gui.Dialogs;
import systems.kinau.fishingbot.io.LogFormatter;
import systems.kinau.fishingbot.io.SettingsConfig;
import systems.kinau.fishingbot.modules.*;
import systems.kinau.fishingbot.network.ping.ServerPinger;
import systems.kinau.fishingbot.network.protocol.NetworkHandler;
import systems.kinau.fishingbot.network.protocol.ProtocolConstants;
import systems.kinau.fishingbot.realms.RealmsAPI;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Properties;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FishingBot {

                    public static String PREFIX;
    @Getter         private static FishingBot instance;
    @Getter         public static Logger log = Logger.getLogger(FishingBot.class.getSimpleName());

    @Getter @Setter private boolean running;
    @Getter         private SettingsConfig config;
    @Getter @Setter private int serverProtocol = ProtocolConstants.MINECRAFT_1_8; //default 1.8
    @Getter @Setter private String serverHost;
    @Getter @Setter private int serverPort;
    @Getter @Setter private AuthData authData;
    @Getter @Setter private boolean wontConnect = false;

    @Getter         private EventManager eventManager;
    @Getter         private CommandRegistry commandRegistry;

    @Getter         private Player player;
    @Getter         private ClientDefaultsModule clientModule;

    @Getter         private Socket socket;
    @Getter         private NetworkHandler net;

    @Getter @Setter private FishingModule fishingModule;

    @Getter         private File logsFolder = new File("logs");
    @Getter         private File accountFile = new File("account.json");

    public FishingBot(CommandLine cmdLine) {
        instance = this;

        try {
            final Properties properties = new Properties();
            properties.load(this.getClass().getClassLoader().getResourceAsStream("fishingbot.properties"));
            PREFIX = properties.getProperty("name") + " v" + properties.getProperty("version") + " - ";
        } catch (Exception ex) {
            PREFIX = "FishingBot vUnknown - ";
            ex.printStackTrace();
        }
        this.eventManager = new EventManager();

        // use command line arguments
        if (cmdLine.hasOption("logsdir")) {
            this.logsFolder = new File(cmdLine.getOptionValue("logsdir"));
            if (!logsFolder.exists()) {
                boolean success = logsFolder.mkdirs();
                if (!success) {
                    System.err.println("Could not create logs-folder. Exiting.");
                    System.exit(1);
                }
            }
        }

        if (cmdLine.hasOption("accountfile")) {
            this.accountFile = new File(cmdLine.getOptionValue("accountfile"));
        }

        // initialize Logger
        log.setLevel(Level.ALL);
        ConsoleHandler ch;
        log.addHandler(ch = new ConsoleHandler());
        log.setUseParentHandlers(false);
        LogFormatter formatter = new LogFormatter();
        ch.setFormatter(formatter);

        // read config

        if (cmdLine.hasOption("config"))
            this.config = new SettingsConfig(cmdLine.getOptionValue("config"));
        else
            this.config = new SettingsConfig("config.json");


        // set logger file handler
        try {
            FileHandler fh;
            if(!logsFolder.exists() && !logsFolder.mkdir() && logsFolder.isDirectory())
                throw new IOException("Could not create logs folder!");
            log.addHandler(fh = new FileHandler(logsFolder.getPath() + "/log%g.log", 0 /* 0 = infinity */, getConfig().getLogCount()));
            fh.setFormatter(new LogFormatter());
        } catch (IOException e) {
            System.err.println("Could not create log!");
            System.exit(1);
        }

        // log config location
        FishingBot.getLog().info("Loaded config from: " + new File(getConfig().getPath()).getAbsolutePath());

        // error if credentials are default credentials
        if (FishingBot.getInstance().getConfig().getUserName().equals("my-minecraft@login.com")) {
            FishingBot.getLog().warning("Credentials not set. Please set your credentials in the config.json.");
            if (!cmdLine.hasOption("nogui"))
                Dialogs.showCredentialsNotSet();
            System.exit(0);
        }

        // authenticate player if online-mode is set
        if(getConfig().isOnlineMode())
            authenticate(accountFile);
        else {
            getLog().info("Starting in offline-mode with username: " + getConfig().getUserName());
            this.authData = new AuthData(null, null, null, getConfig().getUserName());
        }

        String ip = getConfig().getServerIP();
        int port = getConfig().getServerPort();

        //Check rather to connect to realm
        if (getConfig().getRealmId() != -1) {
            RealmsAPI realmsAPI = new RealmsAPI(getAuthData());
            if (getConfig().getRealmId() == 0) {
                List<String> possibleWorldsText = realmsAPI.getPossibleWorlds();
                possibleWorldsText.forEach(getLog()::info);
                FishingBot.getLog().info("Shutting down, because realm-id is not set...");
                if (!cmdLine.hasOption("nogui"))
                    Dialogs.showRealmsWorlds(possibleWorldsText);
                System.exit(0);
            }
            if (getConfig().isRealmAcceptTos())
                realmsAPI.agreeTos();
            else {
                FishingBot.getLog().severe("*****************************************************************************");
                FishingBot.getLog().severe("If you want to use realms you have to accept the tos in the config.json");
                FishingBot.getLog().severe("*****************************************************************************");
                if (!cmdLine.hasOption("nogui"))
                    Dialogs.showRealmsAcceptToS();
                System.exit(0);
            }

            String ipAndPort = null;
            for (int i = 0; i < 5; i++) {
                ipAndPort = realmsAPI.getServerIP(getConfig().getRealmId());
                if (ipAndPort == null) {
                    FishingBot.getLog().info("Trying to receive IP (Try " + (i + 1) + ")...");
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else
                    break;
            }
            if (ipAndPort == null)
                System.exit(0);
            ip = ipAndPort.split(":")[0];
            port = Integer.parseInt(ipAndPort.split(":")[1]);
        }

        //Ping server
        getLog().info("Pinging " + ip + ":" + port + " with protocol of MC-" + getConfig().getDefaultProtocol());
        ServerPinger sp = new ServerPinger(ip, port);
        sp.ping();
    }

    public void start() {
        if(isRunning())
            return;
        connect();
    }

    private boolean authenticate(File accountFile) {
        Authenticator authenticator = new Authenticator(accountFile);
        AuthData authData = authenticator.authenticate();
        if(authData == null) {
            setAuthData(new AuthData(null, null, null, getConfig().getUserName()));
            return false;
        }
        setAuthData(authData);
        return true;
    }

    private void registerCommands() {
        this.commandRegistry = new CommandRegistry();
        getCommandRegistry().registerCommand(new HelpCommand());
        getCommandRegistry().registerCommand(new LevelCommand());
        getCommandRegistry().registerCommand(new EmptyCommand());
        getCommandRegistry().registerCommand(new ByeCommand());
        getCommandRegistry().registerCommand(new StuckCommand());
    }

    private void connect() {
        String serverName = getServerHost();
        int port = getServerPort();

        do {
            try {
                setRunning(true);
                if(isWontConnect()) {
                    setWontConnect(false);
                    ServerPinger sp = new ServerPinger(getServerHost(), getServerPort());
                    sp.ping();
                    if(isWontConnect()) {
                        if(!getConfig().isAutoReconnect())
                            return;
                        try {
                            Thread.sleep(getConfig().getAutoReconnectTime() * 1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        continue;
                    }
                }
                this.socket = new Socket(serverName, port);

                this.net = new NetworkHandler();

                registerCommands();

                this.fishingModule = new FishingModule();
                getFishingModule().enable();

                new HandshakeModule(serverName, port).enable();
                new LoginModule(getAuthData().getUsername()).enable();
                new ChatProxyModule().enable();
                if (getConfig().isStartTextEnabled())
                    new ChatCommandModule().enable();
                this.clientModule = new ClientDefaultsModule();
                getClientModule().enable();
                if (getConfig().isWebHookEnabled())
                    new DiscordModule().enable();
                new ItemHandler(getServerProtocol());
                this.player = new Player();

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }));

                while (running) {
                    try {
                        net.readData();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        getLog().warning("Could not receive packet! Shutting down!");
                        break;
                    }
                }
            } catch (IOException e) {
                getLog().severe("Could not start bot: " + e.getMessage());
            } finally {
                try {
                    if (socket != null)
                        this.socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                if (getClientModule() != null)
                    this.getClientModule().disable();
                if (getFishingModule() != null)
                    this.getFishingModule().disable();
                if (getPlayer() != null)
                    getEventManager().unregisterListener(getPlayer());
                getEventManager().getRegisteredListener().clear();
                getEventManager().getClassToInstanceMapping().clear();
                this.socket = null;
                this.fishingModule = null;
                this.net = null;
                this.player = null;
            }
            if (getConfig().isAutoReconnect()) {
                getLog().info("FishingBot restarts in " + getConfig().getAutoReconnectTime() + " seconds...");
                try {
                    Thread.sleep(getConfig().getAutoReconnectTime() * 1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (getAuthData() == null) {
                    if (getConfig().isOnlineMode())
                        authenticate(accountFile);
                    else {
                        getLog().info("Starting in offline-mode with username: " + getConfig().getUserName());
                        authData = new AuthData(null, null, null, getConfig().getUserName());
                    }
                }
            }
        } while (getConfig().isAutoReconnect());
    }
}
