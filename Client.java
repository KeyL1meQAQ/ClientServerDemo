import javax.swing.plaf.IconUIResource;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.Map;
import java.util.Random;

public class Client {

    private static String SERVER_HOST;
    private static int SERVER_PORT;
    private static int UDP_PORT;

    private static boolean WAITING_FLAG;
    private static boolean ACKED = true;

    static final Object object = new Object();

    private static class UDPReceiver extends Thread {
        private final DatagramSocket SOCKET;

        UDPReceiver(DatagramSocket socket) {
            this.SOCKET = socket;
        }

        @Override
        public void run() {
            super.run();

            byte[] buffer = new byte[20480];
            byte[] ack = "ACK".getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            File file;
            FileOutputStream outputStream = null;
            String deviceName = null;
            String filename = null;

            while (true) {
                try {
                    SOCKET.receive(packet);
                    String info = new String(buffer, 0, packet.getLength());

                    if (info.startsWith("FileInfo")) {
                        String params[] = info.split(" ");
                        deviceName = params[1];
                        filename = params[2];


                        file = new File(deviceName + "_" + filename);
                        file.createNewFile();
                        outputStream = new FileOutputStream(file);

                        printWithWaitingFlag("Start receiving " + filename + " from " + deviceName);

                        DatagramPacket rcvPacket = new DatagramPacket(ack, ack.length, packet.getAddress(), packet.getPort());
                        SOCKET.send(rcvPacket);
                        continue;
                    } else if (info.equals("Finished")) {
                        printWithWaitingFlag("Successfully received " + filename + " from " + deviceName);
                        continue;
                    } else if (info.equals("ACK")) {
                        synchronized (object) {
                            ACKED = true;
                            object.notify();
                        }
                        continue;
                    }
                    outputStream.write(packet.getData(), 0, packet.getLength());
                    outputStream.flush();
                    DatagramPacket rcvPacket = new DatagramPacket(ack, ack.length, packet.getAddress(), packet.getPort());
                    SOCKET.send(rcvPacket);
                } catch (IOException e) {
                    break;
                }
            }
        }
    }

    private static class UDPSender extends Thread {
        private final DatagramSocket SOCKET;
        private final String FILENAME;
        private final String SENDER_NAME;
        private final String RECEIVER_NAME;
        private final InetAddress ADDRESS;
        private final int PORT;

        UDPSender(DatagramSocket socket, String filename, String senderName, String receiverName, InetAddress address,
                  int port) {
            this.SOCKET = socket;
            this.FILENAME = filename;
            this.SENDER_NAME = senderName;
            this.RECEIVER_NAME = receiverName;
            this.ADDRESS = address;
            this.PORT = port;
        }

        @Override
        public void run() {
            super.run();

            byte[] buffer = new byte[20480];

            boolean fileInfoSent = false;
            try {
                FileInputStream inputStream = new FileInputStream(FILENAME);
                while (true) {
                    if (!fileInfoSent) {
                        byte[] fileInfo = ("FileInfo " + SENDER_NAME + " " + FILENAME).getBytes();
                        DatagramPacket packet = new DatagramPacket(fileInfo, fileInfo.length, ADDRESS, PORT);
                        SOCKET.send(packet);
                        fileInfoSent = true;
                    } else {
                        int read = inputStream.read(buffer);
                        if (read == -1) {
                            byte[] finish = "Finished".getBytes();
                            DatagramPacket packet = new DatagramPacket(finish, finish.length, ADDRESS, PORT);
                            SOCKET.send(packet);
                            break;
                        }
                        DatagramPacket packet = new DatagramPacket(buffer, read, ADDRESS, PORT);
                        SOCKET.send(packet);
                    }
                    ACKED = false;
                    synchronized (object) {
                        while (!ACKED)
                            object.wait();
                    }
                }
            } catch (IOException | InterruptedException e) {
                System.out.println("File upload failed");
                e.printStackTrace();
            }
            printWithWaitingFlag("Successfully uploaded " + FILENAME + " to " + RECEIVER_NAME);
        }
    }

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
        ObjectInputStream inputStream;
        ObjectOutputStream outputStream;
        BufferedReader stdInputReader;
        Socket connection;
        DatagramSocket socket;

        // Create UDP socket
        try {
            socket = new DatagramSocket(UDP_PORT);
        } catch (SocketException e) {
            System.out.println("Failed to create UDP socket.");
            return;
        }
        // Create TCP socket
        try {
            connection = new Socket(SERVER_HOST, SERVER_PORT);
            inputStream = new ObjectInputStream(connection.getInputStream());
            outputStream = new ObjectOutputStream(connection.getOutputStream());
            outputStream.flush();
        } catch (IOException e) {
            System.out.println("Failed to create TCP socket. Maybe server is offline or server closed the connection.");
            return;
        }

        stdInputReader = new BufferedReader(new InputStreamReader(System.in));

        UDPReceiver receiver = new UDPReceiver(socket);
        receiver.start();

        try {
            int status = 0;
            String username = "";

            // Receive messages related to log in
            while (status == 0) {
                Map<String, String> msg = (Map<String, String>) inputStream.readObject();
                Map<String, String> info;
                switch (msg.get("status")) {
                    case "100":
                        System.out.println(msg.get("information"));
                        username = msg.get("username");

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
                WAITING_FLAG = true;
                String command = stdInputReader.readLine();
                WAITING_FLAG = false;
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
                    case "AED":
                        handleAED(username, inputStream, outputStream);
                        break;
                    case "OUT":
                        handleOUT(username, outputStream);
                        status = 0;
                        break;
                    case "UVF":
                        handleUVF(params, username, inputStream, outputStream, socket);
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

                socket.close();
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
            ClassNotFoundException {
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

    @SuppressWarnings("unchecked")
    private static void handleAED(String username, ObjectInputStream inputStream, ObjectOutputStream outputStream)
            throws SocketException, EOFException, ClassNotFoundException {
        try {
            Map<String, String> info = Map.of("command", "AED", "username", username);
            outputStream.writeObject(info);
            outputStream.flush();

            Map<String, String> map = (Map<String, String>) inputStream.readObject();
            if (map.get("status").equals("100")) {
                System.out.println("The active edge devices are:");
                System.out.print(map.get("content"));
            } else if (map.get("status").equals("101"))
                System.out.println("AED: ERROR - " + map.get("information"));
        } catch (SocketException | EOFException | ClassNotFoundException e) {
            throw e;
        } catch (IOException e) {
            System.out.println("DTE: ERROR - Failed to get list of active edge devices");
        }
    }

    private static void handleOUT(String username, ObjectOutputStream outputStream)
            throws SocketException, EOFException {
        try {
            Map<String, String> info = Map.of("command", "OUT", "username", username);
            outputStream.writeObject(info);
            outputStream.flush();

            System.out.println("OUT: Bye! " + username);
        } catch (SocketException | EOFException e) {
            throw e;
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("OUT: ERROR - Failed to leave. Force to close connection.");
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleUVF(String[] params, String username, ObjectInputStream inputStream,
                                  ObjectOutputStream outputStream, DatagramSocket socket) throws SocketException,
            EOFException, ClassNotFoundException{

        if (params.length != 3) {
            System.out.println("UVF: ERROR - UVF command requires TWO argument deviceName and filename. " +
                    "Commands are like \"UVF supersmartwatch example1.mp4\"");
            return;
        }

        String address, port;
        try {
            Map<String, String> info = Map.of("command", "AED", "username", username);
            outputStream.writeObject(info);
            outputStream.flush();

            Map<String, String> map = (Map<String, String>) inputStream.readObject();
            String[] deviceInfo = map.get("content").split("\n");

            boolean found = false;
            for (String device: deviceInfo) {
                String[] detail = device.split(", ");
                if (detail[0].substring(detail[0].indexOf(" ") + 1).equals(params[1])) {
                    found = true;
                    address = detail[2].substring(detail[2].lastIndexOf(" ") + 1);
                    port = detail[3].substring(detail[3].lastIndexOf(" ") + 1);

                    File file = new File(params[2]);
                    if (!file.exists()) {
                        System.out.println("UVF: ERROR - File " + params[2] + " not found.");
                        break;
                    }

                    System.out.println("Start uploading " + params[2] + " to " + params[1]);
                    new UDPSender(socket, params[2], username, params[1], InetAddress.getByName(address), Integer.parseInt(port))
                            .start();
                    break;
                }
            }
            if (!found)
                System.out.println("UVF: " + params[1] + " is offline.");
        } catch (SocketException | EOFException e) {
            throw e;
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("UVF: ERROR - Failed to get the IP address and port of this device.");
        }
    }

    private static void printWithWaitingFlag(String content) {
        if (WAITING_FLAG) {
            System.out.println("\n" + content);
            System.out.print("Enter one of the following commands (EDG, UED, SCS, DTE, AED, UVF, OUT): ");
        } else
            System.out.println(content);
    }
}
