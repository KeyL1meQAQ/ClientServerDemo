import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Random;

public class Client {

    private static String SERVER_HOST;
    private static int SERVER_PORT;
    private static int UDP_PORT;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("===== Error usage: java TCPClient SERVER_IP SERVER_PORT =====");
            return;
        }

        SERVER_HOST = args[0];
        SERVER_PORT = Integer.parseInt(args[1]);
        UDP_PORT = Integer.parseInt(args[2]);


        // define socket for client
        ObjectInputStream inputStream = null;
        ObjectOutputStream outputStream = null;
        BufferedReader stdInputReader = null;
        Socket connection = null;
        try {
            connection = new Socket(SERVER_HOST, SERVER_PORT);

            inputStream = new ObjectInputStream(connection.getInputStream());
            outputStream = new ObjectOutputStream(connection.getOutputStream());
            outputStream.flush();

            stdInputReader = new BufferedReader(new InputStreamReader(System.in));

            int status = 0;
            String username = "";

            // Receive messages related to log in
            while (status == 0) {
                Map<String, String> msg = (Map<String, String>) inputStream.readObject();
                Map<String, String> info;
                switch (msg.get("status")) {
                    case "100":
                        System.out.println(msg.get("information"));
                        username = msg.get("information").split(",")[0];

                        info = Map.of("command", "LOGIN", "content", String.valueOf(UDP_PORT));
                        outputStream.writeObject(info);
                        outputStream.flush();

                        status = 1;
                        break;
                    case "101":
                        System.out.println(msg.get("information"));
                        break;
                    case "102":
                        System.out.print(msg.get("prompt"));
                        info = Map.of("command", "LOGIN", "content", stdInputReader.readLine());
                        outputStream.writeObject(info);
                        outputStream.flush();
                        break;
                    case "103":
                        System.out.println(msg.get("information"));
                        status = 2;
                        break;
                }
            }

            // Receive messages related to 6 different operations
            while (status == 1) {
                System.out.print("Enter one of the following commands (EDG, UED, SCS, DTE, AED, UVF, OUT): ");
                String command = stdInputReader.readLine();
                if (command.length() < 3) {
                    System.out.println("Error - \"" + command + "\" is an invalid Command.");
                    continue;
                }

                String[] params = command.split("\\s+");
                switch (params[0]) {
                    case "EDG":
                        handleEDG(params, username);
                        break;
                    case "UED":
                        handleUED(params, username, inputStream, outputStream);
                        break;
                    case "SCS":
                        handleSCS(params, username, inputStream, outputStream);
                        break;
                    case "DTE":
                        handleDTE(params, username, inputStream, outputStream);
                        break;
                    default:
                        System.out.println("Error - \"" + command + "\" is an invalid Command.");
                        break;
                }
            }

        } catch (SocketException | EOFException e) {
            System.out.println("Connection closed by server.");
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        } finally {
            try {
                stdInputReader.close();
                connection.close();
                System.out.println("Connection closed.");
            } catch (IOException e) {
                System.out.println("Failed to close connection.");
                e.printStackTrace();
            }
        }

    }

    private static void handleEDG(String[] params, String username) {
        if (params.length != 3) {
            System.out.println("EDG: ERROR - EDG command requires TWO arguments fileID and " +
                    "dataAmount. Commands are like \"EDG 1 10\"");
            return;
        }
        try {
            int fileID = Integer.parseInt(params[1]);
            int dataAmount = Integer.parseInt(params[2]);

            if (fileID < 1 || dataAmount < 1) {
                System.out.println("EDG: ERROR - The fileID and dataAmount should be positive " +
                        "integers. Commands are like \"EDG 1 10\"");
                return;
            }

            String filename = username + "-" + fileID + ".txt";

            System.out.println("EDG: Generating datafile " + filename + " with " + dataAmount +
                    "data samples...");

            // Create file if the file is not existed
            File file = new File(filename);
            file.createNewFile();
            PrintWriter writer = new PrintWriter(file);

            // Generate and write data into the file
            Random random = new Random();
            for (int i = 0; i < dataAmount; i++) {
                writer.println(random.nextInt(9999) + 1);
            }
            writer.close();
            System.out.println("EDG: Data file generated.");
        } catch (NumberFormatException e) {
            System.out.println("EDG: ERROR - The fileID or dataAmount are not integers, you need to " +
                    "specify the parameter as integers. Commands are like \"EDG 1 10\"");
        } catch (IOException e) {
            System.out.println("EDG: ERROR - Failed to generate datafile.");
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleUED(String[] params, String username, ObjectInputStream inputStream,
                                  ObjectOutputStream outputStream) throws SocketException, EOFException,
            ClassNotFoundException {
        if (params.length != 2) {
            System.out.println("UED: ERROR - UED command requires ONE argument fileID. " +
                    "Commands are like \"UED 1\"");
            return;
        }

        try {
            int fileID = Integer.parseInt(params[1]);
            if (fileID < 1) {
                System.out.println("UED: ERROR - The fileID should be positive integer. Commands are like \"UED 1\"");
                return;
            }

            String filename = username + "-" + fileID + ".txt";
            File file = new File(filename);
            if (!file.exists()) {
                System.out.println("UED: ERROR - The file " + filename + " to be uploaded does not exist.");
                return;
            }
            String content = Files.readString(file.toPath());

            Map<String, String> info = Map.of("command", "UED", "fileID", String.valueOf(fileID),
                    "username", username, "content", content);
            outputStream.writeObject(info);
            outputStream.flush();

            Map<String, String> map = (Map<String, String>) inputStream.readObject();
            if (map.get("status").equals("100"))
                System.out.println("UED: " + map.get("information"));
            else if (map.get("status").equals("101"))
                System.out.println("UED: ERROR - " + map.get("information"));
        } catch (NumberFormatException e) {
            System.out.println("UED: ERROR - The fileID is not integer, you need to " +
                    "specify the parameter as integers. Commands are like \"UED 1\"");
        } catch (SocketException | EOFException | ClassNotFoundException e) {
            throw e;
        } catch (IOException e) {
            System.out.println("UED: ERROR - Failed to upload data file.");
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleSCS(String[] params, String username, ObjectInputStream inputStream,
                                  ObjectOutputStream outputStream) throws SocketException, EOFException,
            ClassNotFoundException {
        if (params.length != 3) {
            System.out.println("SCS: ERROR - SCS command requires TWO argument fileID and computationOperation " +
                    "Commands are like \"SCS 1 SUM\"");
            return;
        }

        try {
            int fileID = Integer.parseInt(params[1]);
            if (fileID < 1) {
                System.out.println("SCS: ERROR - The fileID should be positive integer. Commands are like \"SCS 1 SUM\"");
                return;
            }
            if (!params[2].equals("AVERAGE") && !params[2].equals("MIN") && !params[2].equals("MAX") &&
                    !params[2].equals("SUM")) {
                System.out.println("SCS: ERROR - The computationOperation should one of the following " +
                        "[\"AVERAGE\", \"MIN\", \"MAX\", \"SUM\"]. Commands are like \"SCS 1 SUM\"");
                return;
            }
            Map<String, String> info = Map.of("command", "SCS", "fileID", String.valueOf(fileID),
                    "username", username, "operation", params[2]);
            outputStream.writeObject(info);
            outputStream.flush();

            Map<String, String> map = (Map<String, String>) inputStream.readObject();
            if (map.get("status").equals("100"))
                System.out.println("SCS: " + map.get("information"));
            else if (map.get("status").equals("101") || map.get("status").equals("104"))
                System.out.println("SCS: ERROR - " + map.get("information"));
        } catch (NumberFormatException e) {
            System.out.println("SCS: ERROR - The fileID is not integer, you need to " +
                    "specify the parameter as integers. Commands are like \"SCS 1 SUM\"");
        } catch (SocketException | EOFException | ClassNotFoundException e) {
            throw e;
        } catch (IOException e) {
            System.out.println("UED: ERROR - Failed to use server computation service");
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleDTE(String[] params, String username, ObjectInputStream inputStream,
                                  ObjectOutputStream outputStream) throws SocketException, EOFException,
            ClassNotFoundException{
        if (params.length != 2) {
            System.out.println("DTE: ERROR - DTE command requires ONE argument fileID. " +
                    "Commands are like \"DTE 1\"");
            return;
        }

        try {
            int fileID = Integer.parseInt(params[1]);
            if (fileID < 1) {
                System.out.println("DTE: ERROR - The fileID should be positive integer. Commands are like \"DTE 1\"");
                return;
            }
            Map<String, String> info = Map.of("command", "DTE", "fileID", String.valueOf(fileID),
                    "username", username);
            outputStream.writeObject(info);
            outputStream.flush();

            Map<String, String> map = (Map<String, String>) inputStream.readObject();
            if (map.get("status").equals("100"))
                System.out.println("DTE: " + map.get("information"));
            else if (map.get("status").equals("101") || map.get("status").equals("104"))
                System.out.println("DTE: ERROR - " + map.get("information"));
        } catch (NumberFormatException e) {
            System.out.println("DTE: ERROR - The fileID is not integer, you need to " +
                    "specify the parameter as integers. Commands are like \"DTE 1\"");
        } catch (SocketException | EOFException | ClassNotFoundException e) {
            throw e;
        } catch (IOException e) {
            System.out.println("DTE: ERROR - Failed to use server computation service");
        }
    }
}
