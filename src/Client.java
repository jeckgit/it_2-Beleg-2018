
/* ------------------
   Client
   usage: java Client [Server hostname] [Server RTSP listening port] [Video file requested]
   ---------------------- */

import java.io.*;
import java.net.*;
import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.Timer;

public class Client {

	// GUI
	// ----
	JFrame f = new JFrame("Client");
	JButton setupButton = new JButton("Setup");
	JButton playButton = new JButton("Play");
	JButton pauseButton = new JButton("Pause");
	JButton tearButton = new JButton("Teardown");
	JButton optionButton = new JButton("Option");
	JButton describeButton = new JButton("Describe");
	JPanel mainPanel = new JPanel();
	JPanel buttonPanel = new JPanel();
	JLabel iconLabel = new JLabel();

	JLabel receivedPackages = new JLabel("Recieved Packages: ");
	JLabel lostPackages = new JLabel("Lost Packages: ");
	JLabel package_lost_rate_str = new JLabel("Packagelostrate: ");
	JLabel package_rate_str = new JLabel("Packagerate: ");
	JLabel packages_restored_str = new JLabel("Restored Packages: ");

	JLabel statPanel = new JLabel();
	ImageIcon icon;

	// RTP variables:
	// ----------------
	DatagramPacket rcvdp; // UDP packet received from the server
	DatagramSocket RTPsocket; // socket to be used to send and receive UDP
								// packets
	static int RTP_RCV_PORT = 25000; // port where the client will receive the
										// RTP packets

	Timer timer; // timer used to receive data from the UDP socket
	byte[] buf; // buffer used to store data received from the server

	// RTSP variables
	// ----------------
	// rtsp states
	final static int INIT = 0;
	final static int READY = 1;
	final static int PLAYING = 2;
	static int state; // RTSP state == INIT or READY or PLAYING
	Socket RTSPsocket; // socket used to send/receive RTSP messages
	InetAddress ServerIPAddr;
	// input and output stream filters
	static BufferedReader RTSPBufferedReader;
	static BufferedWriter RTSPBufferedWriter;
	static String VideoFileName; // video file to request to the server
	int RTSPSeqNb = 0; // Sequence number of RTSP messages within the
	int RTSPid = 0; // ID of the RTSP (given by the RTSP Server)
		
	// fec
	FECpacket fec_packet = new FECpacket();
	Timer displaytimer; // timer used to display the next frame
	int imagenumber = 1;

	final static String CRLF = "\r\n";

	// Statistics variables:
	// ------------------
	int packages_received = 0; // amount of packages received
	int packages_lost = 0; // amount of packages lost
	int packages_restored = 0;
	int lastSequencenumber = 0; // seqnr of last package received
	int lasttimestamp = 0;

	// Video constants:
	// ------------------
	static int MJPEG_TYPE = 26; // RTP payload type for MJPEG video

	// --------------------------
	// Constructor
	// --------------------------
	public Client() {

		// build GUI
		// --------------------------

		// Frame
		f.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				System.exit(0);
			}
		});

		// Buttons
		buttonPanel.setLayout(new GridLayout(1, 0));
		buttonPanel.add(setupButton);
		buttonPanel.add(playButton);
		buttonPanel.add(pauseButton);
		buttonPanel.add(tearButton);
		buttonPanel.add(optionButton);
		buttonPanel.add(describeButton);
		setupButton.addActionListener(new setupButtonListener());
		playButton.addActionListener(new playButtonListener());
		pauseButton.addActionListener(new pauseButtonListener());
		tearButton.addActionListener(new tearButtonListener());
		optionButton.addActionListener(new optionButtonListener());
		describeButton.addActionListener(new describeButtonListener());

		// Image display label
		iconLabel.setIcon(null);

		// Statistics
		statPanel.setLayout(new GridLayout(0, 1));
		statPanel.add(receivedPackages);
		statPanel.add(lostPackages);
		statPanel.add(package_lost_rate_str);
		statPanel.add(package_rate_str);
		statPanel.add(packages_restored_str);

		// frame layout
		mainPanel.setLayout(null);
		mainPanel.add(iconLabel);
		mainPanel.add(buttonPanel);
		mainPanel.add(statPanel);
		iconLabel.setBounds(0, 0, 400, 280);
		buttonPanel.setBounds(0, 280, 700, 50);
		statPanel.setBounds(400, 0, 300, 50);

		f.getContentPane().add(mainPanel, BorderLayout.CENTER);
		f.setSize(new Dimension(700, 370));
		f.setVisible(true);

		// init timer
		// --------------------------
		timer = new Timer(20, new timerListener());
		timer.setInitialDelay(0);
		timer.setCoalesce(true);
		
		// init displaytimer
		// --------------------------
		displaytimer = new Timer(40, new displaytimerListener());
		displaytimer.setInitialDelay(2000);
		displaytimer.setCoalesce(true);
		
		// allocate enough memory for the buffer used to receive data from the
		// server
		buf = new byte[15000];
	}

	// ------------------------------------
	// main
	// ------------------------------------
	public static void main(String argv[]) throws Exception {
		// Create a Client object
		Client theClient = new Client();

		// get server RTSP port and IP address from the command line
		// ------------------
		int RTSP_server_port = Integer.parseInt(argv[1]);
		String ServerHost = argv[0];
		theClient.ServerIPAddr = InetAddress.getByName(ServerHost);

		// get video filename to request:
		VideoFileName = argv[2];

		// Establish a TCP connection with the server to exchange RTSP messages
		// ------------------
		theClient.RTSPsocket = new Socket(theClient.ServerIPAddr, RTSP_server_port);

		// Set input and output stream filters:
		RTSPBufferedReader = new BufferedReader(new InputStreamReader(theClient.RTSPsocket.getInputStream()));
		RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(theClient.RTSPsocket.getOutputStream()));

		// init RTSP state:
		
		state = INIT;
	}

	// ------------------------------------
	// Handler for buttons
	// ------------------------------------


	// Handler for Setup button
	// -----------------------
	class setupButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			// System.out.println("Setup Button pressed !");

			if (state == INIT) {
				// Init non-blocking RTPsocket that will be used to receive data
				try {
					// construct a new DatagramSocket to receive RTP packets
					// from the server, on port RTP_RCV_PORT
					// RTPsocket = ...
					RTPsocket = new DatagramSocket(RTP_RCV_PORT);
					// set TimeOut value of the socket to 5msec.
					// ....
					RTPsocket.setSoTimeout(5);
				} catch (SocketException se) {
					System.out.println("Socket exception: " + se);
					System.exit(0);
				}

				// init RTSP sequence number
				RTSPSeqNb = 1;

				// Send SETUP message to the server
				send_RTSP_request("SETUP");

				// Wait for the response
				if (parse_server_response() != 200)
					System.out.println("Invalid Server Response");
				else {
					// change RTSP state and print new state
					state = READY;
					System.out.println("New RTSP state: " + state);
				}
			} // else if state != INIT then do nothing
		}
	}

	// Handler for Play button
	// -----------------------
	class playButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			// System.out.println("Play Button pressed !");

			if (state == READY) {
				// increase RTSP sequence number
				// .....
				++RTSPSeqNb;

				// Send PLAY message to the server
				send_RTSP_request("PLAY");

				// Wait for the response
				if (parse_server_response() != 200)
					System.out.println("Invalid Server Response");
				else {
					// change RTSP state and print out new state
					// .....
					state = PLAYING;
					System.out.println("New RTSP state: PLAYING");

					// start the timer
					timer.start();
					
					// start the displaytimer
					displaytimer.start();
				}
			} // else if state != READY then do nothing
		}
	}

	// Handler for Pause button
	// -----------------------
	class pauseButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			// System.out.println("Pause Button pressed !");

			if (state == PLAYING) {
				// increase RTSP sequence number
				// ........
				++RTSPSeqNb;

				// Send PAUSE message to the server
				send_RTSP_request("PAUSE");

				// Wait for the response
				if (parse_server_response() != 200)
					System.out.println("Invalid Server Response");
				else {
					// change RTSP state and print out new state
					// ........

					state = READY;
					System.out.println("New RTSP state: READY");
					// stop the timer
					timer.stop();
					
					// stop the displaytimer
					displaytimer.stop();
				}
			}
			// else if state != PLAYING then do nothing
		}
	}

	// Handler for Teardown button
	// -----------------------
	class tearButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			// System.out.println("Teardown Button pressed !");

			// increase RTSP sequence number
			// ..........
			++RTSPSeqNb;

			// Send TEARDOWN message to the server
			send_RTSP_request("TEARDOWN");

			// Wait for the response
			if (parse_server_response() != 200)
				System.out.println("Invalid Server Response");
			else {
				// change RTSP state and print out new state
				// ........
				state = INIT;
				System.out.println("New RTSP state: INIT");

				// stop the timer
				timer.stop();
				
				// stop the displaytimer
				displaytimer.stop();

				// exit
				System.exit(0);
			}
		}
	}

	class optionButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			// increase RTSP sequence number
			// ..........
			++RTSPSeqNb;

			// Send OPTION message to the server
			send_RTSP_request("OPTION");
			if (parse_server_response() != 200)
				System.out.println("Invalid Server Response");
			else
				System.out.println("Received response for OPTION");

		}
	}

	class describeButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {
			// increase RTSP sequence number
			// .......
			++RTSPSeqNb;

			// Send DESCRIBE message to the server
			send_RTSP_request("DESCRIBE");
			if (parse_server_response() != 200)
				System.out.println("Invalid Server Response");
			else
				System.out.println("Received response for DESCRIBE");

		}
	}

	// ------------------------------------
	// Handler for timer
	// ------------------------------------

	class timerListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			// Construct a DatagramPacket to receive data from the UDP socket
			rcvdp = new DatagramPacket(buf, buf.length);

			try {
				// receive the DP from the socket:
				RTPsocket.receive(rcvdp);

				// create an RTPpacket object from the DP
				RTPpacket rtp_packet = new RTPpacket(rcvdp.getData(), rcvdp.getLength());			

				if (rtp_packet.getpayloadtype() == 26) { // rtp
					
					// print important header fields of the RTP packet received:
					System.out.println("Got RTP packet with SeqNum # " + rtp_packet.getsequencenumber() + " TimeStamp "
							+ rtp_packet.gettimestamp() + " ms, of type " + rtp_packet.getpayloadtype());

					// compute stats and update the label in GUI
					packages_received++;
					packages_lost += rtp_packet.getsequencenumber() - lastSequencenumber - 1;
					lastSequencenumber = rtp_packet.getsequencenumber();
					// print statistics
					if (rtp_packet.gettimestamp() >= lasttimestamp + 1000) { //Update every 1s
						print_statistics(rtp_packet.gettimestamp());
						lasttimestamp = rtp_packet.gettimestamp();
					}
					
					// add receveid package to ArrayList
					fec_packet.rcvdata(rtp_packet);
					
				} else if (rtp_packet.getpayloadtype() == 127) {
					System.out.println("Got FEC packet with SeqNum # " + rtp_packet.getsequencenumber() + " TimeStamp "
							+ rtp_packet.gettimestamp() + " ms, of type " + rtp_packet.getpayloadtype());

					// add fec packet information
					if (fec_packet.rcvfec(rtp_packet)) {
						packages_restored++;								
					}					

				}

			} catch (InterruptedIOException iioe) {
				// System.out.println("Nothing to read");
			} catch (IOException ioe) {
				System.out.println("Exception caught: " + ioe);
			}
		}
	}
	// ------------------------------------
	// Handler for timer (display)
	// ------------------------------------

		class displaytimerListener implements ActionListener {
			public void actionPerformed(ActionEvent e) {
				try {
					// get image as bytearray from fec packet
					byte[] payload = fec_packet.getjpeg(imagenumber);

					// get an Image object from the payload bitstream
					Toolkit toolkit = Toolkit.getDefaultToolkit();
					Image image = toolkit.createImage(payload, 0, payload.length);

					// display the image as an ImageIcon object
					icon = new ImageIcon(image);
					iconLabel.setIcon(icon);

				} catch (IndexOutOfBoundsException ioobe) {
					// No new frame to show
				} catch (NullPointerException npe) {
					// getjpeg return null
					// no new frame to show
				}
				// next frame
				imagenumber++;
			}
		}

	// ------------------------------------
	// Parse Server Response
	// ------------------------------------
	private int parse_server_response() {
		int reply_code = 0;

		try {
			// parse status line and extract the reply_code:
			String StatusLine = RTSPBufferedReader.readLine();
			// System.out.println("RTSP Client - Received from Server:");
			System.out.println(StatusLine);

			StringTokenizer tokens = new StringTokenizer(StatusLine);
			tokens.nextToken(); // skip over the RTSP version
			reply_code = Integer.parseInt(tokens.nextToken());

			// if reply code is OK get and print the 2 other lines
			if (reply_code == 200) {
				String SeqNumLine = RTSPBufferedReader.readLine();
				System.out.println(SeqNumLine);

				String Line = RTSPBufferedReader.readLine();
				System.out.println(Line);

				// if state == INIT gets the Id from the Line
				tokens = new StringTokenizer(Line);
				String begin = tokens.nextToken();
				if (begin.compareTo("Session:") == 0) {
					RTSPid = Integer.parseInt(tokens.nextToken());
				} else if (begin.compareTo("Content-Base:") == 0) {
					String newLine;
					for (int i = 0; i < 6; i++) {
						newLine = RTSPBufferedReader.readLine();
						System.out.println(newLine);
					}
				}
			}
		} catch (Exception ex) {
			System.out.println("Exception caught: " + ex);
			System.exit(0);
		}

		return (reply_code);
	}

	private void print_statistics(int timestamp) {
		double package_lost_rate = 100. * (double) (packages_lost) / (packages_received + packages_lost);
		double package_rate = 1000. * (double) (packages_received) / timestamp;

		// output
		receivedPackages.setText("Recieved Packages: " + packages_received);
		lostPackages.setText("Lost Packages: " + packages_lost);
		package_lost_rate_str.setText("Packagelostrate: " + Math.round(package_lost_rate * 100) / 100.0);
		package_rate_str.setText("Packagerate: " +  Math.round(package_rate) + "Packages/s");
		packages_restored_str.setText("Restored Packages: " + packages_restored);
	}

	// ------------------------------------
	// Send RTSP Request
	// ------------------------------------

	// .............
	// TO COMPLETE
	// .............

	private void send_RTSP_request(String request_type) {
		try {
			// Use the RTSPBufferedWriter to write to the RTSP socket
			String sentence = "";
			switch (request_type) {
			case "SETUP":
				sentence += "SETUP " + VideoFileName + " RTSP/1.0" + CRLF;
				sentence += "CSeq " + RTSPSeqNb + CRLF;
				sentence += "Transport RTP/UDP; client_port= " + RTP_RCV_PORT + CRLF;
				;
				break;
			case "PLAY":
				sentence += "PLAY " + VideoFileName + " RTSP/1.0" + CRLF;
				sentence += "CSeq " + RTSPSeqNb + CRLF;
				sentence += "Session " + RTSPid + CRLF;
				break;
			case "PAUSE":
				sentence += "PAUSE " + VideoFileName + " RTSP/1.0" + CRLF;
				sentence += "CSeq " + RTSPSeqNb + CRLF;
				sentence += "Session " + RTSPid + CRLF;
				break;
			case "TEARDOWN":
				sentence += "TEARDOWN " + VideoFileName + " RTSP/1.0" + CRLF;
				sentence += "CSeq " + RTSPSeqNb + CRLF;
				sentence += "Session " + RTSPid + CRLF;
				break;
			case "OPTION":
				sentence += "OPTION " + VideoFileName + " RTSP/1.0" + CRLF;
				sentence += "CSeq " + RTSPSeqNb + CRLF;
				sentence += CRLF;
				break;
			case "DESCRIBE":
				sentence += "DESCRIBE " + VideoFileName + " RTSP/1.0" + CRLF;
				sentence += "CSeq " + RTSPSeqNb + CRLF;
				sentence += "Accept: application/sdp" + CRLF;
				break;
			}

			// write the request line:
			// RTSPBufferedWriter.write(...);
			RTSPBufferedWriter.write(sentence);

			RTSPBufferedWriter.flush();
		} catch (Exception ex) {
			System.out.println("Exception caught: " + ex);
			System.exit(0);
		}
	}

}// end of Class Client
