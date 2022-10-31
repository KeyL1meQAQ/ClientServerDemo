import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Server {

    private static int PORT;
    private static int NUMBER_OF_CONSECUTIVE_FAILED_ATTEMPTS;
    private static ServerSocket SOCKET;
    private static Set<String> blockedDevices; // Saving all the temporarily blocked accounts.

    // The multi-threading server structure is from https://webcms3.cse.unsw.edu.au/COMP3331/22T3/resources/80564
    private static class ServerThread extends Thread {
        private final Socket connection;
        private final String clientIP;
        private final String client;

        private String username = "";

        ServerThread(Socket connection) {
            this.connection = connection;
            clientIP = connection.getInetAddress().getHostAddress();
            client = clientIP + ":" + connection.getPort();
        }

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            super.run();

            printLog("Client connected", 0);
            Map<String, String> loginStatus = null;

            ObjectOutputStream outputStream = null;
            ObjectInputStream inputStream = null;
            try {
                outputStream = new ObjectOutputStream(connection.getOutputStream());
                outputStream.flush();
                inputStream = new ObjectInputStream(connection.getInputStream());

                // Start log in procedure
                loginStatus = login(outputStream, inputStream);
                if (loginStatus.get("status").equals("0")) {
                    while (true) {
                        Map<String, String> map = (Map<String, String>) inputStream.readObject();
                        switch (map.get("command")) {
                            case "UED":
                                outputStream.writeObject(handleUED(map.get("username"), map.get("fileID"),
                                        map.get("content")));
                                outputStream.flush();
                                break;
                            case "SCS":
                                outputStream.writeObject(handleSCS(map.get("username"), map.get("fileID"),
                                        map.get("operation")));
                                outputStream.flush();
                                break;
                            case "DTE":
                                outputStream.writeObject(handleDTE(map.get("username"), map.get("fileID")));
                                outputStream.flush();
                                break;
                        }
                    }
                }
            } catch (EOFException | SocketException e) {
                printLog("Client disconnected abnormally. Close the connection.", 0);
            } catch (ClassNotFoundException | IOException e) {
                System.out.println("SERVER ERROR: " + e.getMessage());
            } finally {
                String status = loginStatus == null ? null : loginStatus.get("status");
                String username = loginStatus == null ? null : loginStatus.get("username");
                try {
                    // Close the connection
                    connection.close();

                    printLog("Connection to client closed", 0);

                    // If we need to block the account for 10 seconds
                    if (status != null && status.equals("1")) {
                        blockedDevices.add(username);
                        printLog("Account: " + username + " blocked", 0);
                        Thread.sleep(10000);

                        blockedDevices.remove(username);
                        printLog("Account: " + username + " unblocked", 0);
                    }
                } catch (IOException e) {
                    printLog("Failed to close connection to client", 0);
                } catch (InterruptedException e) {
                    blockedDevices.remove(username);
                    printLog("Failed to block Account: " + username, 0);
                }
            }
        }

        private void printLog(String content, int mode) {
            String s = username.equals("") ? "" : "(" + username + ")";
            switch (mode) {
                case 0:
                    System.out.println("Client " + client + s + " - "  + content);
                    break;
                case 1:
                    System.out.println("Send to Client " + client + s + " - " + content);
                    break;
                case 2:
                    System.out.println("Received from Client " + client + s + " - " + content);
                    break;
                case 3:
                    System.out.println("Server - " + content);
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, String> login(ObjectOutputStream outputStream, ObjectInputStream inputStream) throws
                IOException, ClassNotFoundException {
            int failAttempts = 0;
            String username = null;
            Map<String, String> info;

            while (failAttempts < NUMBER_OF_CONSECUTIVE_FAILED_ATTEMPTS) {
                // Prompt the client for username
                if (username == null) {
                    info = Map.of("command", "LOGIN", "status", "102", "prompt", "Username: ");
                    outputStream.writeObject(info);
                    outputStream.flush();

                    Map<String, String> map = (Map<String, String>) inputStream.readObject();
                    String input = map.get("content");

                    if (checkUsernamePassword(input, null))
                        username = input;
                    else {
                        info = Map.of("command", "LOGIN", "status", "101", "information",
                                "The username is invalid, please check your input username and try again");
                        outputStream.writeObject(info);
                        outputStream.flush();
                    }
                } else {
                    if (blockedDevices.contains(username)) {
                        info = Map.of("command", "LOGIN", "status", "103", "information",
                                "Your account is blocked due to multiple authentication failures. " +
                                        "Please try again later.");
                        outputStream.writeObject(info);
                        outputStream.flush();
                        return Map.of("status", "2", "Username", username);
                    }

                    info = Map.of("command", "LOGIN", "status", "102", "prompt", "Password: ");
                    outputStream.writeObject(info);
                    outputStream.flush();

                    Map<String, String> map = (Map<String, String>) inputStream.readObject();
                    String input = map.get("content");
                    if (checkUsernamePassword(username, input)) {
                        info = Map.of("command", "LOGIN", "status", "100", "information",
                                username + ", you have successfully logged in. Welcome!");
                        outputStream.writeObject(info);
                        outputStream.flush();

                        map = (Map<String, String>) inputStream.readObject();
                        SynchronizedFileHandler.handleEdgeDeviceLog(0, username, clientIP, map.get("content"));
                        return Map.of("status", "0", "username", username);
                    }

                    failAttempts++;
                    if (failAttempts == NUMBER_OF_CONSECUTIVE_FAILED_ATTEMPTS)
                        info = Map.of("command", "LOGIN", "status", "103", "information",
                                "Your account is blocked due to multiple authentication failures. " +
                                        "Please try again later.");
                    else
                        info = Map.of("command", "LOGIN", "status", "101", "information",
                                "Wrong password, please try again!");
                    outputStream.writeObject(info);
                    outputStream.flush();
                }
            }
            return Map.of("status", "1", "username", username == null ? "" : username);
        }

        private boolean checkUsernamePassword(String username, String password) throws IOException {
            FileInputStream fileInputStream = new FileInputStream("credentials.txt");
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(fileInputStream));
            String line;
            if (password == null) {
                while ((line = bufferedReader.readLine()) != null) {
                    if (username.equals(line.split(" ")[0]))
                        return true;
                }
            } else {
                while ((line = bufferedReader.readLine()) != null) {
                    if (username.equals(line.split(" ")[0]) && password.equals(line.split(" ")[1]))
                        return true;
                }
            }
            return false;
        }

        private Map<String, String> handleUED(String username, String fileID, String content) {
            String filename = username + "-" + fileID + ".txt";
            try {
                Files.createDirectories(Path.of(username));
                File file = new File(username + "/" + filename);
                PrintWriter writer = new PrintWriter(file);
                writer.print(content);
                writer.close();

                LineNumberReader reader = new LineNumberReader(new FileReader(file));
                while (reader.readLine() != null);
                SynchronizedFileHandler.addUploadLog(username, fileID, String.valueOf(reader.getLineNumber()));
                reader.close();

                return Map.of("command", "UED", "status", "100", "information",
                        "File " + filename + " uploaded.");
            } catch (IOException e) {
                e.printStackTrace();
                return Map.of("command", "UED", "status", "101", "information",
                        "File " + filename + " upload failed.");
            }
        }

        private Map<String, String> handleSCS(String username, String fileID, String operation) {
            String filename = username + "-" + fileID + ".txt";
            try {
                File file = new File(username + "/" + filename);

                List<Integer> integers = new ArrayList<>();
                BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
                String line = bufferedReader.readLine();
                while (line != null) {
                    integers.add(Integer.parseInt(line));
                    line = bufferedReader.readLine();
                }
                bufferedReader.close();

                switch (operation) {
                    case "AVERAGE":
                        double average = integers.stream().mapToDouble(a -> a).average().orElse(0.0);
                        return Map.of("command", "SCS", "status", "100", "information",
                                "AVERAGE of " + filename + ": " + average);
                    case "MIN":
                        int min = integers.stream().mapToInt(a -> a).min().orElse(0);
                        return Map.of("command", "SCS", "status", "100", "information",
                                "MIN of " + filename + ": " + min);
                    case "MAX":
                        int max = integers.stream().mapToInt(a -> a).max().orElse(0);
                        return Map.of("command", "SCS", "status", "100", "information",
                                "MAX of " + filename + ": " + max);
                    case "SUM":
                        int sum = integers.stream().mapToInt(a -> a).sum();
                        return Map.of("command", "SCS", "status", "100", "information",
                                "SUMSC of " + filename + ": " + sum);
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return Map.of("command", "SCS", "status", "104", "information", "File: "
                        + filename + " not found on server");
            } catch (IOException e) {
                e.printStackTrace();
            }
            return Map.of("command", "SCS", "status", "101", "information", "Server " +
                    "computing service failed");
        }

        private Map<String, String> handleDTE(String username, String fileID) {
            String filename = username + "-" + fileID + ".txt";

            try {
                File file = new File(username + "/" + filename);

                LineNumberReader reader = new LineNumberReader(new FileReader(file));
                while (reader.readLine() != null);
                SynchronizedFileHandler.addUploadLog(username, fileID, String.valueOf(reader.getLineNumber()));

                if (file.delete()) {
                    SynchronizedFileHandler.addDeleteLog(username, fileID, String.valueOf(reader.getLineNumber()));
                    reader.close();
                    return Map.of("command", "DTE", "status", "100", "information", "File: " +
                            filename + " deleted.");
                } else
                    throw new IOException();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return Map.of("command", "DTE", "status", "104", "information", "File: " +
                        filename + " not found on the server");
            } catch (IOException e) {
                e.printStackTrace();
                return Map.of("command", "DTE", "status", "101", "information", "File: " +
                        filename + " delete failed");
            }
        }
    }

    private static class SynchronizedFileHandler {
        private static synchronized void handleEdgeDeviceLog(int mode, String... args) throws IOException {
            switch (mode) {
                case 0:
                    File file = new File("edge-device-log.txt");
                    file.createNewFile(); // Create this log file if it is not existed

                    // Get the sequence number for current record
                    LineNumberReader reader = new LineNumberReader(new FileReader(file));
                    while (reader.readLine() != null);
                    int seq = reader.getLineNumber() + 1;
                    reader.close();

                    // Write the record to the end of the file
                    FileWriter writer = new FileWriter(file, true);
                    String content = join(String.valueOf(seq), getDateTime(), args[0], args[1], args[2]);
                    writer.write(content);
                    writer.close();
            }
        }

        private static synchronized void addUploadLog(String... args) throws IOException {
            File file = new File("upload-log.txt");
            file.createNewFile(); // Create this log file if it is not existed

            // Write the record to the end of the file
            FileWriter writer = new FileWriter(file, true);
            String content = join(args[0], getDateTime(), args[1], args[2]);
            writer.write(content);
            writer.close();
        }

        private static synchronized void addDeleteLog(String... args) throws IOException {
            File file = new File("deletion-log.txt");
            file.createNewFile(); // Create this log file if it is not existed

            // Write the record to the end of the file
            FileWriter writer = new FileWriter(file, true);
            String content = join(args[0], getDateTime(), args[1], args[2]);
            writer.write(content);
            writer.close();
        }

        private static String getDateTime() {
            return DateTimeFormatter.ofPattern("dd MMMM yyyy HH:mm:ss", Locale.US).format(LocalDateTime.now());
        }

        private static String join(String... args) {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i == args.length - 1) {
                    result.append(args[i]).append("\n");
                } else {
                    result.append(args[i]).append("; ");
                }
            }
            return result.toString();
        }
    }

    public static void main(String[] args) {
        PORT = Integer.parseInt(args[0]);
        NUMBER_OF_CONSECUTIVE_FAILED_ATTEMPTS = Integer.parseInt(args[1]);

        blockedDevices = new HashSet<>();

        try {
            SOCKET = new ServerSocket(PORT);
            System.out.println("Server is running on port - " + PORT);

            while (true) {
                Socket connection = SOCKET.accept();
                new ServerThread(connection).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
