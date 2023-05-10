package net.blockhost.crashtheserver;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;

public class CrashTheServer extends JavaPlugin implements CommandExecutor {
    private String apiToken;

    @Override
    public void onEnable() {
        // Set the command executor to this plugin
        getCommand("startserver").setExecutor(this);
        getCommand("startserver").setTabCompleter(this);
        this.saveDefaultConfig();

        this.getServer().getPluginManager().registerEvents(new JoinEvent(this), this);

        // Get the absolute path to the private key file in the plugin's data folder
        File privateKeyFile = new File(getDataFolder(), "privatekey.key");
        String privateKeyPath = privateKeyFile.getAbsolutePath();
        String privateKeyName = privateKeyFile.getName();

        // Print the private key path in the console
        getLogger().info("Private key path: " + privateKeyPath);
        getLogger().info("Private key: " + privateKeyFile);
        getLogger().info("Private key: " + privateKeyName);

        // Get the API token from the config file
        apiToken = getConfig().getString("hetzner-api-token");
        if (apiToken == null) {
            getLogger().warning("hetzner-api-token not found in config.yml");
        }
    }



    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("startserver")) {
            if (args.length == 1) {
                // Tab complete server name
                return Arrays.asList("name:");
            } else if (args.length == 2) {
                // Tab complete flat:on/off
                return Arrays.asList("flat:on", "flat:off");
            } else if (args.length == 3) {
                // Tab complete whitelist:on/off
                return Arrays.asList("whitelist:on", "whitelist:off");
            } else if (args.length == 4) {
                // Tab complete worldedit:on/off
                return Arrays.asList("worldedit:on", "worldedit:off");
            }
        }
        return null;
    }

    boolean flat = false;
    boolean whitelist = false;
    boolean worldedit = false;
    String serverName = null;
    //String serverId = null;
    private int serverId;

    private final List<String> notAllowedNames = new ArrayList<>();
    private final Map<String, Long> cooldowns = new HashMap<>();

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("startserver")) {
            // Check if the player has provided the required arguments
            if (args.length < 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /startserver name:serverName flat:on/off whitelist:on/off worldedit:on/off");
                return true;
            }

            // Parse the command arguments
            for (String arg : args) {
                if (arg.startsWith("name:")) {
                    serverName = arg.substring(5);
                } else if (arg.equals("flat:on")) {
                    flat = true;
                } else if (arg.equals("flat:off")) {
                    flat = false;
                } else if (arg.equals("whitelist:on")) {
                    whitelist = true;
                } else if (arg.equals("whitelist:off")) {
                    whitelist = false;
                }
                else if (arg.equals("worldedit:on")) {
                    worldedit = true;
                } else if (arg.equals("worldedit:off")) {
                    worldedit = false;
                }
            }

            // Check if the server name and flat option were provided
            if (serverName == null) {
                sender.sendMessage(ChatColor.RED + "Usage: /startserver name:serverName flat:on/off worldedit:on/off");
                return true;
            }

            // Check if the server name is allowed
            if (notAllowedNames.contains(serverName.toLowerCase())) {
                sender.sendMessage(ChatColor.RED + "This server name is not allowed at the moment.");
                return true;
            }

            if (serverName.length() > 16) {
                sender.sendMessage(ChatColor.RED + "Server name must be 16 characters or less.");
                return true;
            }

            CommandSender player = sender;

            if (!player.hasPermission("crash.srv.1") && !player.hasPermission("crash.srv.2") && !player.hasPermission("crash.srv.3")) {
                sender.sendMessage(ChatColor.RED + "You cannot start a server right now. You can only start 3 servers per week.");
                return true;
            }

            if (player.hasPermission("crash.srv.3")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " permission settemp crash.srv.3 false 7d");
            } else if (player.hasPermission("crash.srv.2") && (!player.hasPermission("crash.srv.3"))) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " permission settemp crash.srv.2 false 7d");
            } else if (player.hasPermission("crash.srv.1") && (!player.hasPermission("crash.srv.3")) && (!player.hasPermission("crash.srv.2"))) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " permission settemp crash.srv.1 false 7d");
            }

            // Check if the server name is valid
            if (serverName.equalsIgnoreCase("main") ||
                    serverName.contains("proxy") ||
                    serverName.contains("hub") ||
                    serverName.contains("lobby") ||
                    serverName.contains("nigger") ||
                    serverName.contains("nigga") ||
                    serverName.contains("n1gga") ||
                    serverName.contains("n1gger") ||
                    serverName.contains("sex") ||
                    serverName.contains("kys") ||
                    serverName.contains("fag") ||
                    serverName.contains("rape") ||
                    serverName.contains("porn") ||
                    serverName.contains("fuck") ||
                    serverName.contains("velocity")) {
                sender.sendMessage(ChatColor.RED + "This server name is not allowed.");
                return true;
            }

            // Add the server name to the not allowed list for 60 minutes
            notAllowedNames.add(serverName.toLowerCase());
            cooldowns.put(serverName.toLowerCase(), System.currentTimeMillis() + (60 * 60 * 1000));

            CompletableFuture.runAsync(() -> {
                try {
                    createServer(serverName, flat, sender);
                } catch (IOException e) {
                    sender.sendMessage(ChatColor.RED + "Error creating server: " + e.getMessage());
                    e.printStackTrace();
                }
            });

            return true;
        }

        return false;
    }


    private void createServer(String serverName, boolean flat, CommandSender sender ) throws IOException {
        // Set the curl command and arguments

        String[] command = {
                "curl",
                "-X",
                "POST",
                "-H",
                "Authorization: Bearer " + apiToken,
                "-H",
                "Content-Type: application/json",
                "-d",
                "{\"automount\":false,\"datacenter\":\"fsn1-dc14\",\"image\":\"ubuntu-22.04\",\"labels\":{},\"name\":\"" + serverName + "\",\"server_type\":\"cax21\",\"networks\":[2855591],\"public_net\":{\"enable_ipv4\":true,\"enable_ipv6\":false},\"ssh_keys\":[\"ssh2\"],\"start_after_create\":true}",
                "https://api.hetzner.cloud/v1/servers"
        };



        // Execute the curl command
        Process process2 = Runtime.getRuntime().exec(command);
        BufferedReader reader2 = new BufferedReader(new InputStreamReader(process2.getInputStream()));

        // Read the response from the command and print it to the console
        String line2;
        StringBuilder responseBuilder2 = new StringBuilder();
        while ((line2 = reader2.readLine()) != null) {
            responseBuilder2.append(line2);
        }
        String response2 = responseBuilder2.toString();
        System.out.println(response2);

        // Parse the response as a JSON object and get the server ID
        JSONObject responseJson2 = new JSONObject(response2);
        int serverId = responseJson2.getJSONObject("server").getInt("id");
        sender.sendMessage(ChatColor.GREEN + "Server created: " + serverName + ", ID: " + serverId);

        BukkitScheduler scheduler3 = Bukkit.getScheduler();
        scheduler3.runTaskLater(this, () -> {
            CompletableFuture.runAsync(() -> {
                try {
                    deleteServer(serverId, sender);
                } catch (IOException e) {
                    sender.sendMessage(ChatColor.RED + "Error deleting server: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        }, 6600); // Change this to 6600 (ticks) to wait for 55 minutes

        BukkitScheduler scheduler2 = Bukkit.getScheduler();
        scheduler2.runTaskLater(this, () -> {
            CompletableFuture.runAsync(() -> {
                try {
                    getServerDetails(serverId, sender, serverName);
                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "Error getting private IP: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        }, 300); // Change this to 300 (ticks) to wait for 15 seconds
    }

    private void deleteServer(int serverId, CommandSender sender) throws IOException {
        // Set the curl command and arguments
        String[] command = {
                "curl",
                "-X",
                "DELETE",
                "-H",
                "Authorization: Bearer " + apiToken,
                "https://api.hetzner.cloud/v1/servers/" + serverId
        };

        // Execute the curl command
        Process process = Runtime.getRuntime().exec(command);
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        // Read the response from the command and print it to the console
        String line;
        StringBuilder responseBuilder = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            responseBuilder.append(line);
        }
        String response = responseBuilder.toString();
        System.out.println(response);

        // Send a message to the player indicating that the server has been deleted
        sender.sendMessage(ChatColor.YELLOW + "Server deleted: " + serverId);
    }


    private void getServerDetails(int serverId, CommandSender sender, String serverName) throws IOException {
        // Set the curl command to get server details
        String[] command2 = {
                "curl",
                "-H",
                "Authorization: Bearer " + apiToken,
                "https://api.hetzner.cloud/v1/servers/" + serverId
        };

        // Execute the curl command
        Process process = Runtime.getRuntime().exec(command2);
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        // Read the response from the command and print it to the console
        String line;
        StringBuilder responseBuilder = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            responseBuilder.append(line);
        }
        String response = responseBuilder.toString();
        System.out.println(response);

        // Parse the response as a JSON object and get the private_net IP address
        JSONObject responseJson = new JSONObject(response);
        JSONArray privateNetArray = responseJson.getJSONObject("server").getJSONArray("private_net");
        if (privateNetArray.length() == 0) {
            sender.sendMessage(ChatColor.RED + "Error: private_net array is empty");
            return;
        }
        String privateIp = privateNetArray.getJSONObject(0).getString("ip");

        // Send the private IP address to the player
        //sender.sendMessage(ChatColor.GREEN + "Private IP: " + privateIp);

        // Connect to the server via SSH
        getLogger().info("Connecting to server via SSH...");

        CompletableFuture.runAsync(() -> {
            try {
                Player player = (Player) sender;
                connectAndSetUpServer(serverName, privateIp, sender, player.getUniqueId(), sender.getName());
            } catch (Exception e) {
                getLogger().severe("Failed to establish SSH connection: " + e.getMessage());
                e.printStackTrace();
            }
        });

    }

    private void connectAndSetUpServer(String serverName, String privateIp, CommandSender sender, UUID uuid, String playerName) throws Exception {

        File privateKeyFile = new File(getDataFolder(), "privatekey.key");

        SshClient client = SshClient.setUpDefaultClient();
        client.start();
        ConnectFuture connectFuture = client.connect("root", privateIp, 22);
        connectFuture.await();
        ClientSession session = connectFuture.getSession();

        try {
            Path privateKeyPath = Paths.get(privateKeyFile.getAbsolutePath());
            Iterator<KeyPair> keyPairIterator = SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(privateKeyFile.getName()), Files.newInputStream(privateKeyPath), null).iterator();
            KeyPair keyPair = keyPairIterator.hasNext() ? keyPairIterator            .next() : null;

            session.addPublicKeyIdentity(keyPair);
            session.auth().verify(10000);
            getLogger().info("SSH connection established.");

            // Run each command with the specified delays
            BukkitScheduler scheduler = Bukkit.getScheduler();
            AtomicInteger delay = new AtomicInteger(100); // 5 seconds delay (1 tick = 50ms)

            // Install Java
            scheduler.runTaskLater(this, () -> {
                runCommandWithLogging(session, "apt update && apt --yes --force-yes install openjdk-17-jre-headless", "Installed Java", "Failed to download Java", false);
            }, delay.getAndAdd(900));

            scheduler.runTaskLater(this, () -> {
                runCommandWithLogging(session, "screen -S server", "Made screen server", "Failed to make screen server", false);
            }, delay.getAndAdd(20));

            scheduler.runTaskLater(this, () -> {
                runCommandWithLogging(session, "cd /root", "Went to /root", "Failed to go to /root", false);
            }, delay.getAndAdd(20));

            // Make the server directory
            scheduler.runTaskLater(this, () -> {
                runCommandWithLogging(session, "mkdir server", "Made directory server", "Failed to make directory server", false);
            }, delay.getAndAdd(40));

            // Go to /server
            scheduler.runTaskLater(this, () -> {
                runCommandWithLogging(session, "cd /root/server", "Went to /server", "Failed to go to /server", false);
            }, delay.getAndAdd(20));

            // Set "online-mode: false" in server.properties
            String levelType = flat ? "minecraft\\:flat" : "minecraft\\:normal";
            scheduler.runTaskLater(this, () -> {
                String command = String.format("echo 'online-mode=false%nlevel-type=%s%nenforce-whitelist=%b' > server.properties", levelType, whitelist);
                runCommandWithLogging(session, command, "Server.properties updated", "Failed to update server.properties", false);
            }, delay.getAndAdd(20));
            // Set "eula: true" in eula.txt
            scheduler.runTaskLater(this, () -> {
                runCommandWithLogging(session, "echo 'eula=true' > eula.txt", "eula.txt eula is true now", "Failed to make eula.txt", false);
            }, delay.getAndAdd(20));

            // Set "bungeecord: true" in spigot.yml
            scheduler.runTaskLater(this, () -> {
                runCommandWithLogging(session, "printf 'settings:\\n  bungeecord: true\\n' > spigot.yml", "spigot.yml bungeecord is true now", "Failed to make spigot.yml", false);
            }, delay.getAndAdd(20));

            scheduler.runTaskLater(this, () -> {
                String opsJson = "[\n" +
                        "  {\n" +
                        "    \"uuid\": \"" + uuid + "\",\n" +
                        "    \"name\": \"" + playerName + "\",\n" +
                        "    \"level\": 4,\n" +
                        "    \"bypassesPlayerLimit\": false\n" +
                        "  }\n" +
                        "]";
                runCommandWithLogging(session, "echo '" + opsJson + "' > ops.json", "ops.json created", "Failed to create ops.json", false);
            }, delay.getAndAdd(20));

            scheduler.runTaskLater(this, () -> {
                runCommandWithLogging(session, "mkdir plugins", "Made directory plugins", "Failed to make directory plugins", false);
            }, delay.getAndAdd(20));

            /*

            scheduler.runTaskLater(this, () -> {
                runCommandWithLogging(session, "mkdir plugins/TAB", "Made directory TAB", "Failed to make directory TAB", false);
            }, delay.getAndAdd(20));

            scheduler.runTaskLater(this, () -> {
                String configYaml = "header-footer:\n" +
                        "  enabled: true\n" +
                        "  disable-in-worlds:\n" +
                        "    - disabledworld\n" +
                        "  disable-in-servers:\n" +
                        "    - disabledserver\n" +
                        "  header:\n" +
                        "    - ''\n" +
                        "    - '&4&l&oCrashTheServer&7&l&o.net'\n" +
                        "    - ''\n" +
                        "    - '&eThere are &b%online% &eplayers online.'\n" +
                        "    - ''\n" +
                        "  footer:\n" +
                        "    - ''\n" +
                        "    - '&eInstance Name: " + serverName + "'\n" +
                        "    - '&eInstance ID: " + serverId + "'\n" +
                        "    - '        &7Discord: &8https://discord.crashtheserver.net&r        '\n" +
                        "    - ''\n";

                runCommandWithLogging(session, "echo '" + configYaml + "' > plugins/TAB/config.yml", "TAB config updated", "Failed to update TAB config", false);
            }, delay.getAndAdd(20));

            // Make the server directory
            scheduler.runTaskLater(this, () -> {
                runCommandWithLogging(session, "wget -P ./plugins https://github.com/NEZNAMY/TAB/releases/download/3.3.2/TAB.v3.3.2.jar", "Downloaded TAB", "Failed to download TAB", false);
            }, delay.getAndAdd(100));

             */

            if(worldedit){
                scheduler.runTaskLater(this, () -> {
                    runCommandWithLogging(session, "wget -P ./plugins https://cdn.discordapp.com/attachments/1105571133526392874/1105591774438752296/worldedit-bukkit-7.2.14.jar", "Downloaded WorldEdit", "Failed to download WorldEdit", false);
                }, delay.getAndAdd(100));
            }

            // Download the paper jar
            scheduler.runTaskLater(this, () -> {
                runCommandWithLogging(session, "wget https://api.papermc.io/v2/projects/paper/versions/1.19.4/builds/525/downloads/paper-1.19.4-525.jar", "Downloaded paper jar", "Failed to download paper jar", false);
            }, delay.getAndAdd(600));

            // Rename the jar to server.jar
            scheduler.runTaskLater(this, () -> {
                runCommandWithLogging(session, "mv paper-1.19.4-525.jar server.jar", "Renamed paper jar to server.jar", "Failed to rename to server jar", false);
            }, delay.getAndAdd(20));

            // Start the server
            scheduler.runTaskLater(this, () -> {
                runCommandWithLogging(session, "java -Xmx6144M -Xms6144M -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true -jar server.jar", "Started the minecraft server.", "Failed to start the server", false);
                String port = "25565"; // Default Minecraft server port
                sender.sendMessage(ChatColor.GREEN + "Server started. Join with /server " + serverName);
                saveServerInfoToFile(serverName, privateIp, port);
            }, delay.getAndAdd(20));

            // Close the session and stop the client after all tasks are executed
            waitForLastTaskAndCloseSession(session, client, delay.get());


        } catch (Exception e) {
            getLogger().severe("Failed to establish SSH connection: " + e.getMessage());
            e.printStackTrace();
        } finally {
            //session.close();
            //client.stop();
        }
    }

    // Helper method to run commands with logging
    private void runCommandWithLogging(ClientSession session, String command, String successMessage, String failureMessage, boolean closeSession) {
        CompletableFuture.runAsync(() -> {
            try {
                runCommand(session, command);
                getLogger().info(successMessage);
                if (closeSession) {
                    session.close();
                }
            } catch (IOException e) {
                getLogger().severe(failureMessage + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void runCommand(ClientSession session, String command) {
        CompletableFuture.runAsync(() -> {
            try {
                ChannelExec channel = session.createExecChannel(command);
                channel.setOut(System.out);
                channel.open();
                channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0);
                channel.close(true);
            } catch (IOException e) {
                getLogger().severe("Failed to run command: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void waitForLastTaskAndCloseSession(ClientSession session, SshClient client, int delay) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                session.close();
                //client.stop();
                getLogger().info("SSH session closed");
            } catch (Exception e) {
                getLogger().severe("Failed to close SSH session: " + e.getMessage());
                e.printStackTrace();
            }
        }, delay);
    }

    private void saveServerInfoToFile(String serverName, String privateIp, String port) {
        File serverInfoFile = new File(getDataFolder(), "servers.txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(serverInfoFile, true))) {
            writer.write(serverName + "," + privateIp + "," + port);
            writer.newLine();
        } catch (IOException e) {
            getLogger().severe("Failed to save server info to file: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
