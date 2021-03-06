package edu.ucsb.cs56.games.client_server;

import java.io.*;
import java.net.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import javax.swing.*;

import edu.ucsb.cs56.games.client_server.Controllers.Controller;
import edu.ucsb.cs56.games.client_server.Models.MessageModel;
import edu.ucsb.cs56.games.client_server.Models.ClientModel;
import edu.ucsb.cs56.games.client_server.Models.ResModel;
import edu.ucsb.cs56.games.client_server.Models.UsernameModel;
import edu.ucsb.cs56.games.client_server.Views.ChessViewPanel;
import edu.ucsb.cs56.games.client_server.Views.GameViewPanel;
import edu.ucsb.cs56.games.client_server.Views.GomokuViewPanel;
import edu.ucsb.cs56.games.client_server.Views.LobbyViewPanel;
import edu.ucsb.cs56.games.client_server.Views.OfflineViewPanel;
import edu.ucsb.cs56.games.client_server.Views.TicTacToeViewPanel;

/**
 * JavaClient is the main runnable client-side application, it allows users to connect to a server on a specific port
 * and chat with other connected users, as well as play games like tic tac toe, gomoku, and chess with them
 * it is composed of a user list, a message box, input box and send button for chatting, and a panel area to display
 * the lobby or current game
 *
 * @author Joseph Colicchio
 * @version for CS56, Choice Points, Winter 2012
 */

//start a java message client that tries to connect to a server at localhost:X
public class JavaClient implements KeyListener {
    public static JavaClient javaClient;

    Socket sock;
    InputStreamReader stream;
    BufferedReader reader;
    PrintWriter writer;

    private ArrayList<ClientModel> clients;
    ArrayList<Integer> services;
    
    ArrayList<MessageModel> messages;

    JFrame frame;
    Container container;
    GameViewPanel canvas;//the actual canvas currently being used by the gui
    GameViewPanel canvasRef;//a reference to the current canvas being used by the game logic
    JTextField inputBox;
    JButton sendButton;
    JEditorPane outputBox;

    JList userList;
    DefaultListModel listModel;

    private int id;
    String name;
    int location;

    boolean[] Keys;
    
    InputReader thread;
    RefreshThread refreshThread;
    private boolean connected;

    public static void main(String [] args) {
        javaClient = new JavaClient();
    }

    public JavaClient() {
        ResModel.init(this.getClass());
        frame = new JFrame("Java Games Online");
        frame.setSize(640, 512);
        frame.setMinimumSize(new Dimension(480,512));
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent winEvt) {
                if(thread != null)
                    thread.running = false;
                if(isConnected())
                    sendMessage("DCON;Window Closed");

                System.exit(0);
            }
        });

        
        container = frame.getContentPane();
        canvas = new OfflineViewPanel(JavaServer.IP_ADDR,JavaServer.PORT);
        canvasRef = canvas;
        container.add(BorderLayout.CENTER,canvas);

        JPanel southPanel = new JPanel(new BorderLayout());
        container.add(BorderLayout.SOUTH, southPanel);

        SendListener listener = new SendListener();
        inputBox = new JTextField();
        inputBox.addActionListener(listener);
        sendButton = new JButton("Send");
        sendButton.addActionListener(listener);
        southPanel.setFocusable(true);
        canvas.setFocusable(true);

        canvas.addKeyListener(this);
        canvas.addMouseListener(canvas);
        inputBox.addKeyListener(this);

        southPanel.add(BorderLayout.EAST, sendButton);
        southPanel.add(BorderLayout.CENTER, inputBox);

        listModel = new DefaultListModel();


        userList = new JList(listModel);
        MouseListener mouseListener = new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int index = userList.locationToIndex(e.getPoint());
                    //follow player into game
                    UsernameModel user = (UsernameModel)(listModel.getElementAt(index));
                    if(user != null)
                        sendMessage("MSG;/follow "+user.getName());
                }
            }
        };
        userList.addMouseListener(mouseListener);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setLayoutOrientation(JList.VERTICAL);
        userList.setVisibleRowCount(-1);
        userList.setCellRenderer(new MyCellRenderer());

        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setAlignmentX(JScrollPane.LEFT_ALIGNMENT);
        JPanel userPanel = new JPanel();
        userPanel.setLayout(new BorderLayout());
        container.add(BorderLayout.WEST, userPanel);
        userPanel.add(BorderLayout.CENTER,userScroll);
        FollowButton followButton = new FollowButton();
        MessageButton messageButton = new MessageButton();
        
        JPanel menuPanel = new JPanel();
        menuPanel.setLayout(new BoxLayout(menuPanel,BoxLayout.X_AXIS));
        menuPanel.add(followButton);
        menuPanel.add(Box.createHorizontalGlue());
        menuPanel.add(messageButton);
        userPanel.add(BorderLayout.SOUTH,menuPanel);
        userScroll.setPreferredSize(new Dimension(160,100));

        outputBox = new JEditorPane("text/html", "");
        JScrollPane outputScroll = new JScrollPane(outputBox);
        outputBox.setEditable(false);
        southPanel.add(BorderLayout.NORTH, outputScroll);
        outputScroll.setPreferredSize(new Dimension(100, 100));

        frame.setVisible(true);

        Keys = new boolean[255];
        for(int i=0;i<255;i++)
            Keys[i] = false;

        //TODO: use the standardized list!!

        location = -1;
    }

    /** followbutton allows users to follow their friends into the game they're playing
     * this can also be achieved by double-clicking on a name in the user list
     */
    class FollowButton extends JButton implements  ActionListener {
        public FollowButton() {
            super("Follow");
            addActionListener(this);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            UsernameModel user = (UsernameModel)userList.getSelectedValue();
            if(user != null)
               sendMessage("MSG;/follow "+user.getName());
        }
    }

    /** messagebutton fills the input box with a command to send the specified user a message
     *
     */
    class MessageButton extends JButton implements  ActionListener {
        public MessageButton() {
            super("Message");
            addActionListener(this);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            UsernameModel user = (UsernameModel)userList.getSelectedValue();
            if(user == null)
                return;

            inputBox.setText("/msg " + user.getName() + " ");
            //give inputbox focus
            inputBox.requestFocus();
        }
    }


    public void init() {
        setClients(new ArrayList<ClientModel>());
        services = new ArrayList<Integer>();
        messages = new ArrayList<MessageModel>();
    }

    /** updateClients updates the client list with the names and locations of everyone on the server
     * should be called whenever a user joins, leaves, or changes locations
     */
    public void updateClients() {
        SwingUtilities.invokeLater(
            new Runnable() {
                public void run() {
                    synchronized (getClients()) {
                        listModel.clear();
                        if(location < 0)
                            return;
                        listModel.addElement(new UsernameModel(name,null,2));
                        for(int i=getClients().size()-1;i>=0;i--) {
                            ClientModel client = getClients().get(i);
                            if(client != null) {
                                if(client.getId() == getId())
                                    continue;
                                if(client.getLocation() == location || services.size() <= client.getLocation())
                                    listModel.insertElementAt((new UsernameModel(client.getName(),null,0)),1);
                                else {
//                                    System.out.println(client.location+", "+serviceList.size()+", "+services.size());
                                    listModel.addElement(new UsernameModel(client.getName()," ("+client.getLocation()+":"+Controller.getGameType(services.get(client.getLocation()))+")",1));
                                }
                            }
                        }
                    }
                }
            }
        );
    }

    /** updateMessages updates the message box, and then scrolls down to the bottom to see the most recent
     * message. should be called whenever a new message is received
     */
    public void updateMessages() {
        SwingUtilities.invokeLater(
            new Runnable() {
                public void run() {
                    String content = "";
                    for(int i=0;i<messages.size();i++) {
                        content += messages.get(i).toString() + "<br>";
                    }
                    outputBox.setText(content);
                    int caret = outputBox.getDocument().getLength()-1;
                    if(caret > 0)
                        outputBox.setCaretPosition(caret);
                }
            }
        );
    }

    /** connect is called when the player enters an IP and port number, and clicks connect
     * it attempts to connect the player to the associated running server if it exists
     * @param ip - the ip address string to connect to
     * @param port - the port number
     */
    public void connect(String ip, int port) {
        if(isConnected())
            return;
        try {
            System.out.println("Connecting to "+ip+":"+port);
            sock = new Socket(ip,port);
            System.out.println("Connected");
            setConnected(true);
            init();
            stream = new InputStreamReader(sock.getInputStream());
            reader = new BufferedReader(stream);
            writer = new PrintWriter(sock.getOutputStream());
            sendMessage("ACKNOWLEDGE ME!");
            thread = new InputReader();
            thread.start();
            refreshThread = new RefreshThread(this);
            refreshThread.start();
        } catch(IOException ex) {
            ex.printStackTrace();
            System.out.println("unable to connect");
            //System.out.println("quitting...");
            //System.exit(1);
        }
    }

    //public void update() {
    //    for(int i=0;i<clients.size();i++)
    //        if(clients.get(i) != null)
    //            clients.get(i).update();
    //}

    /** handleMessage is passed a string which has been sent from the server
     * it attempts to resolve the request but may forward it to the active game panel, if applicable
     * it manages things like users connecting, disconnecting, receiving private messages, nick changes, etc
     * whereas the game panel handles data regarding the current game
     * @param string the data from the server to handle
     */
    public void handleMessage(String string) {
        if(string.indexOf("CON;") == 0) {
            int pid = Integer.parseInt(string.substring(4));
            System.out.println("Client "+pid+" has connected");
            while(getClients().size() <= pid)
                getClients().add(null);
            if(getClients().get(pid) == null)
                getClients().set(pid, new ClientModel(pid));
            else
                sendMessage("INFO;");
            messages.add(new MessageModel(getClients().get(pid).getName()+" connected", "Server",true,false));
            updateClients();
            updateMessages();
        } else if(string.indexOf("DCON[") == 0) {
            String[] data = string.substring(5).split("]");
            int pid = Integer.parseInt(data[0]);
            System.out.println("Client " + pid + " has disconnected: " + data[1]);
            if(getClients().size() > pid && getClients().get(pid) != null) {
                messages.add(new MessageModel(getClients().get(pid).getName() + " disconnected: "+data[1], "Server", true, false));
                getClients().set(pid, null);
            }
            updateClients();
            updateMessages();
            if(pid == getId())
                thread.running = false;
        } else if(string.indexOf("MSG[") == 0) {
            String[] data = string.substring(4).split("]");
            int pid = Integer.parseInt(data[0]);
            if(getClients().size() <= pid || getClients().get(pid) == null)
                return;
            String msg = string.substring(4+data[0].length()+1);
            System.out.println("Client "+pid+" said "+msg);
            if(getClients().size() > pid) {
                messages.add(new MessageModel(msg,getClients().get(pid).getName(),false,false));
                updateMessages();
            }
        } else if(string.indexOf("PMSG[") == 0) {
            String[] data = string.substring(5).split("]");
            int pid = Integer.parseInt(data[0]);
            String msg = string.substring(5+data[0].length()+1);
            System.out.println("Client "+pid+" privately said "+msg);
            if(getClients().size() > pid) {
                messages.add(new MessageModel(msg,getClients().get(pid).getName(), true, false));
                updateMessages();
            }
        } else if(string.indexOf("RMSG[") == 0) {
            String[] data = string.substring(5).split("]");
            int pid = Integer.parseInt(data[0]);
            String msg = string.substring(5+data[0].length()+1);
            if(getClients().size() > pid) {
                messages.add(new MessageModel(msg,getClients().get(pid).getName(),true,true));
                updateMessages();
            }
        } else if(string.indexOf("SMSG;") == 0) {
            String msg = string.substring(5);
            if(msg != null && msg.length() > 0) {
                messages.add(new MessageModel(msg,"Server",true,false));
                updateMessages();
            }
        } else if(string.indexOf("ID;") == 0) {
            setId(Integer.parseInt(string.substring(3)));
            if(name == null)
                name = "User"+getId();

            sendMessage("CON;");
            sendMessage("NAME;"+name);
            sendMessage("INFO;");
            System.out.println(location);
        } else if(string.indexOf("ALL;") == 0) {
            String[] connected = string.substring(4).split(";");
            for(int i=0;i<connected.length;i++) {
                String[] info = connected[i].split(",");
                if(getClients().size() <= i)
                    getClients().add(null);
                if(connected[i].equals(","))
                    continue;
                if(info[0].equals("")) {
                    if(getClients().get(i) != null)
                        getClients().set(i, null);
                } else {
                    getClients().set(i, new ClientModel(i, info[0], Integer.parseInt(info[1])));
                    if(getId() == i)
                        changeLocation(Integer.parseInt(info[1]));
                }
            }
            //the problem is here, we need to have something else removing the clients from the list and re-adding them
            //otherwise when the thing redraws, it'll freak out
            updateClients();
        } else if(string.indexOf("SERV;") == 0) {
            String[] serv = string.substring(5).split(",");
            for(int i=0;i<serv.length;i++) {
                if(services.size() <= i)
                    services.add(null);
                services.set(i, Integer.parseInt(serv[i]));
            }
            updateClients();
            changeLocation(location);
        } else if(string.indexOf("NEW;") == 0) {
            services.add(Integer.parseInt(string.substring(4)));
        } else if(string.indexOf("NAME[") == 0) {
            String[] data = string.substring(5).split("]");
            int pid = Integer.parseInt(data[0]);
            String pname = data[1];
            if(getClients().size() <= pid)
                return;
            if(getClients().get(pid) == null)
                getClients().set(pid, new ClientModel(getId(), pname, 0));
            //messages.add(new edu.ucsb.cs56.W12.jcolicchio.issue535.Message(clients.get(pid).name+" changed his name to "+pname, "Server",true,false,clients.get(0).getColor()));
            getClients().get(pid).setName(pname);
            if(pid == getId())
                name = pname;
            updateClients();
            updateMessages();
        } else if(string.indexOf("MOVED[") == 0) {
            String[] data = string.substring(6).split("]");
            int pid = Integer.parseInt(data[0]);
            getClients().get(pid).setLocation(Integer.parseInt(data[1]));
            if(pid == getId()) {
                changeLocation(getClients().get(getId()).getLocation());
            }
            updateClients();
            updateMessages();
        }
        canvasRef.handleMessage(string);
    }

    /** changes the location of the client, in order to generate a service panel associated with
     * that location to start interacting with the specified service
     * @param L the service id number
     */
    public void changeLocation(int L) {
        if(location == L)
            return;
        location = L;
        if(location == -1) {
            canvasRef = new OfflineViewPanel(JavaServer.IP_ADDR,JavaServer.PORT);
        } else {

            int serviceType = services.get(location);
            if(serviceType == 0)
                canvasRef = new LobbyViewPanel();
            else if(serviceType == 1)
                canvasRef = new TicTacToeViewPanel();
            else if(serviceType == 2)
                canvasRef = new GomokuViewPanel();
            else if(serviceType == 3)
                canvasRef = new ChessViewPanel();
        }

        SwingUtilities.invokeLater(
            new Runnable() {
                public void run() {
                    messages = new ArrayList<MessageModel>();
                    //updateMessages();
                    container.remove(canvas);
                    canvas = canvasRef;
                    container.add(BorderLayout.CENTER, canvas);
                    canvas.addMouseListener(canvas);
                    //frame.validate();
                    container.validate();
                }
            }
        );
    }

    /** sends a message to the server, which might be a request for information, game data,
     * or a literal message to be broadcast to all users in the message box
     * @param string a string of data to send to the server
     */
    public void sendMessage(String string) {
        writer.println(string);
        writer.flush();
    }

    @Override
    public void keyTyped(KeyEvent keyEvent){ }

    @Override
    public void keyPressed(KeyEvent keyEvent){
        Keys[keyEvent.getKeyCode()] = true;
    }

    @Override
    public void keyReleased(KeyEvent keyEvent){
        Keys[keyEvent.getKeyCode()] = false;
    }

    public ArrayList<ClientModel> getClients() {
		return clients;
	}

	public void setClients(ArrayList<ClientModel> clients) {
		this.clients = clients;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public boolean isConnected() {
		return connected;
	}

	public void setConnected(boolean connected) {
		this.connected = connected;
	}

	/** listens for the send button's action and sends a message, if connected
     *
     */
    class SendListener implements ActionListener {
        public SendListener() {

        }

        public void actionPerformed(ActionEvent event) {
            String message = inputBox.getText();
            if(message.length() == 0)
                return;

            inputBox.setText("");
            if(isConnected()) {
                sendMessage("MSG;"+message);
            }
        }
    }

    /** input reader waits for data from the server and forwards it to the client
     *
     */
    class InputReader extends Thread implements Runnable {
        public boolean running;
        public void run() {
            String line;
            running = true;
            try {
                while(running && (line = reader.readLine()) != null) {
                    System.out.println("incoming... "+line);
                    handleMessage(line);
                }
            } catch(SocketException ex) {
                ex.printStackTrace();
                System.out.println("lost connection to server...");
            } catch(Exception ex) {
                ex.printStackTrace();
                System.out.println("crashed for some other reason, disconnecting...");
                writer.println("DCON;"+getId());
                writer.flush();
            }

            try{
                sock.close();
            }catch(IOException e){
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
            setConnected(false);
            outputBox.setText("");
            updateClients();
            changeLocation(-1);
            System.out.println("quitting, cause thread ended");
            //System.exit(0);
        }
    }

}

/** renders usernames with bold or italics
 * useful when a user is in another location
 * or to highlight the client's username
 */
class MyCellRenderer extends DefaultListCellRenderer {

    public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

        UsernameModel user = (UsernameModel)value;
        if (user.style == 2) {// <= put your logic here
            c.setFont(c.getFont().deriveFont(Font.BOLD));
        } else if(user.style == 1) {
            c.setFont(c.getFont().deriveFont(Font.ITALIC));
        } else {
            c.setFont(c.getFont().deriveFont(Font.PLAIN));
        }
        return c;
    }
}

/**
 * refresh thread constantly repaints the application
 */
class RefreshThread extends Thread implements Runnable {
    public boolean running;
    JavaClient javaClient;
    public RefreshThread(JavaClient client) {
        running = false;
        javaClient = client;
    }

    public void run() {
        running = true;
        while(running) {
            //javaClient.update();
            SwingUtilities.invokeLater(
                    new Runnable() {
                        public void run() {
                            javaClient.canvas.repaint();
                        }
                    }
            );
            try {
                Thread.sleep(50);
            } catch(InterruptedException e) {
                e.printStackTrace();
                System.out.println("refresh thread broke");
            }
        }
    }
}