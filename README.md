# IT2 Beleg

``` Autor: Jörg Eckhold (s73589) ```


``` Abgabedatum: 14.01.2018 ```

## Projekt

In diesem Projekt wurde ein Client und ein Server für Videostreaming unter Nutzung des Real-Time-Streaming-Protokolls RTSP implementiert. Die eigentlichen Videodaten werden mittels Real-Time-Protokoll RTP übertragen. Der Schutz der übertragenen Pakete erfolgt mittels FEC.

![UML Digramm](/doc/img/uml-generell.JPG)

## Server 

Der Server dient dazu um aus den mjpeg-Dateien RTP-Pakete und FEC-Pakete zu erzeugen und sie dann an den Client zu schicken und die vom Client geschickten Anfragen zu beantworten.
Die einzelnen Frames werden mithilfe der ``` VideoStream ``` Klasse ausgelesen. Die einzelnen JPEG-Bilder werden als Byte-Array an den Server übergeben, der daraus die RTP-Pakete erzeugt. Diese RTP-Pakete werden dann in Datagrampackets umgewandelt und an den Client gesendet.  

in __Server.java__:
```java
// get next frame to send from the video, as well as its size
int image_length = video.getnextframe(buf);

// Builds an RTPpacket object containing the frame
RTPpacket rtp_packet = new RTPpacket(MJPEG_TYPE, imagenb, imagenb * FRAME_PERIOD, buf, image_length);

// get to total length of the full rtp packet to send
int packet_length = rtp_packet.getlength();

// retrieve the packet bitstream and store it in an array of bytes
byte[] packet_bits = new byte[packet_length];
rtp_packet.getpacket(packet_bits);

// send the packet as a DatagramPacket over the UDP socket
senddp = new DatagramPacket(packet_bits, packet_length, ClientIPAddr, RTP_dest_port);	

if (packageSlider.getValue() < ThreadLocalRandom.current().nextInt(0, 100) ) {
	RTPsocket.send(senddp);
}
```
Außerdem kann man am Server GUI mithilfe eines Sliders die Paketverlustrate künstlich einstellen.

![Server Picture](/doc/img/Server.JPG)
## Client


Der Client dient als GUI und kann eine Anzahl von befehlen vom Server abfragen: ```Setup```,```Play```,```Pause```,```Teardown```,```Option```,```Describe```.
Außerdem werden verbindungsrelevante Statistiken angezeigt.

![Client Picture](/doc/img/Client.JPG)

Der Client besitzt 2 Timer. Der erste Timer ```timerListener``` dient dazu, die Pakete vom Server zu empfangen.

in __Client.java__:
```java
// receive the DP from the socket
RTPsocket.receive(rcvdp);
```

Der zweite Timer ```displayListener``` dient dazu die empfangenen Bilder anzuzeigen.

in __Client.java__:
```java
// get an Image object from the payload bitstream
Toolkit toolkit = Toolkit.getDefaultToolkit();
Image image = toolkit.createImage(payload, 0, payload.length);

// display the image as an ImageIcon object
icon = new ImageIcon(image);
iconLabel.setIcon(icon);
```
## RTPpacket

in __RTPpacket.java__:

Ein RTP-Paket beinhaltet die Daten über einen Frame des zu streamenden Videos. Die für die Wiedergabe wichtigen Metadaten werden im Header gespeichert, der wiefolgt aufgebaut ist:
```java
//header[0]
header[0] = (byte) (header[0] | Version << 6);
header[0] = (byte) (header[0] | Padding << 5);
header[0] = (byte) (header[0] | Extension << 4);
header[0] |= (byte) CC;
// header[1]
header[1] = (byte) (header[1] | Marker << 7);
header[1] |= (byte) PayloadType;

// header[2] && header[3]
header[2] = (byte) (SequenceNumber >> 8);
header[3] = (byte) (SequenceNumber);

header[4] = (byte) (TimeStamp >> 24); // [xxxxxxxx|--------|--------|--------]
header[5] = (byte) ((TimeStamp >> 16) & 0x000000FF); // [--------|xxxxxxxx|--------|--------]
header[6] = (byte) ((TimeStamp >> 8) & 0x000000FF); // [--------|--------|xxxxxxxx|--------]
header[7] = (byte) (TimeStamp & 0x000000FF); // [--------|--------|--------|xxxxxxxx]
header[8] = (byte) (Ssrc >> 24); // Ssrc same as Timestamp
header[9] = (byte) ((Ssrc >> 16) & 0x000000FF);
header[10] = (byte) ((Ssrc >> 8) & 0x000000FF);
header[11] = (byte) (Ssrc & 0x000000FF);

```

## FECpacket

Das FEC-Paket dient dazu, die bei einer Übertragung verloren gegangene Daten wiederherzustellen. Dies wird mittels des Forward-Error-Correction (FEC) Verfahren realisiert. Zum einen kann der Server mit dieser Klasse ein RTP-Paket erstellen, das die Eigenschaften eines FEC-Pakets enthällt, zum anderen kann der Client verloren gegangene RTP-Pakete wiederherzustellen.

### Sender-/Serverseite

Jedes mal, wenn der Server einen neuen Frame übertragen will, werden die zu übertragenen Bytes auch an das FEC-paket übergeben.

in __Server.java__:
```java
// get next frame to send from the video, as well as its size
int image_length = video.getnextframe(buf);


// Builds an RTPpacket object containing the frame
RTPpacket rtp_packet = new RTPpacket(MJPEG_TYPE, imagenb, imagenb * FRAME_PERIOD, buf, image_length);

// get to total length of the full rtp packet to send
int packet_length = rtp_packet.getlength();

// retrieve the packet bitstream and store it in an array of bytes
byte[] packet_bits = new byte[packet_length];
rtp_packet.getpacket(packet_bits);

// send the packet as a DatagramPacket over the UDP socket
senddp = new DatagramPacket(packet_bits, packet_length, ClientIPAddr, RTP_dest_port);	

if (packageSlider.getValue() < ThreadLocalRandom.current().nextInt(0, 100) ) {
RTPsocket.send(senddp);
}	
//create FECpacket
fecpacket.xordata(rtp_packet);
```
Das neue RTP-Paket und das FEC-Paket des vorherigen Frames werden XOR-verknüpft. Zu erst wird, falls notwendig, die Größe des FEC-datenpuffers angespasst. Danach erfolgt die XOR-Operation.

in __FECpacket.java__:

```java
 void xordata(byte[] data, int data_length) { // nimmt Nutzerdaten entgegen
		
    	//Wenn Packet größer ist ... Größe anpassen
    	if (data_length > this.fecdata.length) {
			// Erstelle temporäres Array für neue Daten
			byte[] tmpdata = new byte[data_length];

			// temporäres neues größeres array wird mit Daten gefüllt
			for (int i = 0; i < this.fecdata.length; i++) {
				tmpdata[i] = this.fecdata[i];
			}

			// Paket hat nun die selbe länge wie übergebenes Paket
			this.fecdata = tmpdata;
			this.data_length = data_length;
		}


    	// ||hier XOR Operation||
		for (int i = 0; i < data_length; i++) {
			this.fecdata[i] = (byte) (this.fecdata[i] ^ data[i]);
		}
		packages++;
	}
```
Wenn das FEC-Paket soviele Frames erhalten hat, wie am Anfang in der FEC_group definiert wurde(entweder als Überabeparameter in der Konsole oder der Standartwert), dann wird ein neues RTP-Paket erstellt, welches die Daten der letzten XOR-Verknüpfung beinhaltet. Danach wird das FEC-Paket resetet.

in __Server.java:__:

```java
if (fecpacket.packages == k) {
  // Create RTPpacket from FECpacket
  RTPpacket fec_packet = fecpacket.createRTPpacket(imagenb);

  // Get data from RTPpacket
  int fec_length = fec_packet.getlength();
  byte[] fec_bits = new byte[fec_length];
  fec_packet.getpacket(fec_bits);

  // Send RTPpacket
  senddp = new DatagramPacket(fec_bits, fec_length, ClientIPAddr, RTP_dest_port);					

  if (packageSlider.getValue() < ThreadLocalRandom.current().nextInt(0, 100) ) {
 	 RTPsocket.send(senddp);
  }


  // Create new FECpacket
  fecpacket = new FECpacket(k);
}

```
Beim Aufruf des FEC-Konstruktors wird das noch vorhandene Paket resetet:

in __FECpacket.java__:
```java
 public FECpacket( int FEC_group) {
	 reset();
	 this.FEC_group = FEC_group;
 }
 void reset() {
		FEC_group = 0;
		packages = 0;
		data_length = 0;
		fecdata = new byte[0];
		to_frame = 0;
		rtp_nrs = new ArrayList<>();
	}
```

### RECEIVER-/Clientseite

Wenn beim Client ein RTP-Packet ankommt, ...

in __Client.java__:
```java
if (rtp_packet.getpayloadtype() == 26) { // rtp
					
// print important header fields of the RTP packet received: 
System.out.println("Got RTP packet with SeqNum # " + rtp_packet.getsequencenumber() + " TimeStamp " + rtp_packet.gettimestamp() + " ms, of type " + rtp_packet.getpayloadtype());
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

}
```
wird zunächst mit der Funktion ```fec_packet.rcvdata(rtp_packet);``` überprüft, ob seine Sequenznummer größer als die Sequenznummer des vorhergehenden Pakets. Wenn dem so ist wird es in einer Arraylist, dem ```Mediastack```, gespeichert. Zusätzlich wird die Sequenznummer auf die des erhaltenen Pakets gesetzt und die Anzahl der erhaletenen Pakete hochgezählt. 

in __FECpacket.java__:

```java
public void rcvdata( RTPpacket rtp) {
    if (rtp.getsequencenumber() > lastSqNr) {
    	mediastack.add(rtp);
		packages++;
		lastSqNr = rtp.getsequencenumber();
	}
 }
 ```
 Wenn der Client nun aber ein FEC-Paket erhält, ...
 
 __Client.java__ 
 ```java
 else if (rtp_packet.getpayloadtype() == 127) {
 System.out.println("Got FEC packet with SeqNum # " + rtp_packet.getsequencenumber() + " TimeStamp "
 + rtp_packet.gettimestamp() + " ms, of type " + rtp_packet.getpayloadtype());

// add fec packet information
if (fec_packet.rcvfec(rtp_packet)) {
  	packages_restored++;								
  }
}
```
 wird zunächst aus dem ersten Byte des Payloads die Größe der ```FEC_group``` ausgelesen und gesetzt. Die bereits im ```Mediastack``` enthaltenen Daten werden nund mit den restlichen übergebenen Bytes XOR-verknüpft.

in __FECpacket.java__:
 ```java
 // speichert FEC-Daten, Nr. eines Bildes der Gruppe    
    public boolean rcvfec(RTPpacket rtp) {
    	
    	boolean restored;

    	// bekommt FEC_group vom ersten payload(data) Element
    	FEC_group = rtp.payload[0];
    	this.data_length = rtp.getpayload_length() - 1;
    	
    	//daten sind alles außer erstes Element (FEC-grp)
    	byte[] newdata = Arrays.copyOfRange(rtp.payload, 1, rtp.getpayload_length());
    	xordata(newdata, this.data_length);
    	
    	to_frame = rtp.getsequencenumber();
    	
    	restored = checkDisplaylist();
    	//gibt true zurück wenn Paket widerhergestellt wurde
    	//spart statistik schritt in dem man im Client einfach auf true testet und wiederhergestellte pakete dort hochzählt 
    	
    	//setzt daten zurück für neue Pakete
    	reset();
    	return restored;
    }
 ```
 In der Funktion ```checkDisplaylist();``` wird geprüft ob alle RTP-Pakete die in der Reichweite des empfangenen FEC-Pakets liegen. Ist dies der Fall passiert nichts. Wenn mehr als ein RTP-Paket verloren gegangen ist, können diese auch nicht wieder hergestellt werden. Wenn jedoch genau ein Paket verloren gegangen ist wird dessen Paketnummer heraus gefunden und die verlorenen Daten wiederhergestellt. Das wiederhergestellte Paket wird dann an die entsprechende Stelle im ```Mediastack``` geschrieben. Anschließend werden die Daten des FEC-Pakets wieder zurückgesetzt.

in __FECpacket.java__:
 ```java
 //Checkt ob alle RTP Pakete im Mediastack vorhanden sind 
    private boolean checkDisplaylist() {    	
    	//für jedes rtp paket im Mediastack Sequenznummer in Liste geschrieben
		for (int i = 0; i < mediastack.size(); i++) {
			if (mediastack.get(i).getsequencenumber() > this.to_frame - this.FEC_group) {
				rtp_nrs.add(mediastack.get(i).getsequencenumber());
			}
		}
	
		//wenn Anzahl an Frame NUmbers mit FEC group übereinstimmt --> alle Pakete erhalten
		if (rtp_nrs.size() == this.FEC_group) {
			// Alle Pakete erhalten
			return false;

		} else if (rtp_nrs.size() < this.FEC_group - 1) {
			//  wenn weniger nummern in rtp_nrs stehen als (FEC_group - 1)  --> mind 2 pakete verloren
			// nicht widerherstellbar
			return false;

		} else {
			// genau ein Paket verloren --> kann widerhergestellt werden
			//verlorene Pketnummer herausfinden
			int missingnr = get_missing_nr();
			
			byte[] missingdata = get_missing_data();

			// restore missing package
			RTPpacket missingpacket = new RTPpacket(26, missingnr, 0, missingdata, missingdata.length);

			// create empty temp list
			List<RTPpacket> tmp = new ArrayList<>();

			// remove bigger packages than missingpackage
			while ((mediastack.size() > 0)
					&& (mediastack.get(mediastack.size() - 1).getsequencenumber() > missingnr)) {
				tmp.add(0, mediastack.get(mediastack.size() - 1));
				mediastack.remove(mediastack.size() - 1);
			}

			// add missingpacket at right position
			mediastack.add(missingpacket);

			// add elements in tmp to displaypackages
			while (tmp.size() > 0) {
				mediastack.add(tmp.get(0));
				tmp.remove(0);
			}

			return true;
		}
	}
```


