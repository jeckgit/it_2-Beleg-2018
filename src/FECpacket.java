import java.util.*;

public class FECpacket
{
    int FEC_group;       // FEC-Gruppengröße
     
    List<RTPpacket> mediastack =  new ArrayList<RTPpacket>(); // Puffer für Medienpakete
    byte[] fecdata; // Puffer für FEC-Data 
    int data_length; // Packetlänge
    int packages; //Anzahl an Paketen um zu erkennen ob k erreicht wird
    int to_frame;
    List<Integer> rtp_nrs;
    
    private int lastSqNr = 0;

    
    final static int FEC_TYPE = 127; //Payloadtype Fec
	final static int FRAME_PERIOD = 40; //Anzahl an Frames

    
    // SENDER --------------------------------------
	 public FECpacket( int FEC_group) {
	    	reset();
	    	this.FEC_group = FEC_group;
	    }

    // RECEIVER ------------------------------------   
    public FECpacket()  {    	
    	this(0);
    }
       
    void reset() {
		FEC_group = 0;
		packages = 0;
		data_length = 0;
		fecdata = new byte[0];
		to_frame = 0;
		rtp_nrs = new ArrayList<>();
	}
    
    // ----------------------------------------------
    // *** SENDER *** 
    // ----------------------------------------------
    
    // speichert Nutzdaten zur FEC-Berechnung
    public void setdata( byte[] data, int data_length) {
    	this.data_length = data_length;
		for (int i = 0; i < this.data_length; i++) {
			this.fecdata[i] = data[i];
		}
    }
    
    // holt fertiges FEC-Paket, Rückgabe: Paketlänge 
    public int getdata( byte[] data) {
    	for(int i = 0; i < this.data_length; i++) {
    		this.fecdata[i] = data[i];
    	}
    	return  this.data_length;
    }
    
    //XOR
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
    
    //Übergabe der FEC Informationen an die XOR Funktion
    void xordata(RTPpacket rtppacket) {
		xordata(rtppacket.payload, rtppacket.payload_size);
	}
    
    //erzeugt zur übergebenen imagenb neues RTP Packet zu erzeugen
    // gibt k bzw FEC-grp an die erste Stelle des payloads
    RTPpacket createRTPpacket (int imagenb) {
    	byte[] fecdata = new byte[this.data_length + 1];
    	//an erste stelle wird FEC-grp geschrieben (k)
    	fecdata[0] = (byte)packages;
    	//danach werden die restlichen Daten des Pakets kopiert 
    	System.arraycopy(this.fecdata, 0, fecdata, 1, this.data_length);
    	
    	return new RTPpacket(FEC_TYPE, imagenb, imagenb * FRAME_PERIOD, fecdata, this.data_length + 1);
    	
    }
    // ------------------------------------------------
    // *** RECEIVER *** 
    // ------------------------------------------------
    // speichert UDP-Payload, Nr. des Bildes
    public void rcvdata( RTPpacket rtp) {
    	if (rtp.getsequencenumber() > lastSqNr) {
    		mediastack.add(rtp);
			packages++;
			lastSqNr = rtp.getsequencenumber();
		}
    }

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
    
    //gibt verlorene packetnr zurück
    int get_missing_nr() {
		int next = this.to_frame - this.FEC_group;		
		for (int i = 0; i < rtp_nrs.size(); i++) {
			if (rtp_nrs.get(i) == next + 1) {
				next = rtp_nrs.get(i);
			} else {
				return next + 1;
			}
		}		// letztes Paket fehlt

		return this.to_frame;
	}
    
    //verlorene Daten widerherstellen
    byte[] get_missing_data() {
		for (int i = 0; i < mediastack.size(); i++) {
			if (mediastack.get(i).getsequencenumber() > this.to_frame - this.FEC_group) {
				xordata(mediastack.get(i));
			}
		}
		return this.fecdata;
	}
    
    //Checkt ob alle Pakete im Mediastack vorhanden sind 
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
			// get missing packages in RTPpackages
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
    
    // übergibt vorhandenes/korrigiertes Paket oder Fehler (null)    
    public byte[] getjpeg( int nr) {
    	if (mediastack.size() > 0) {
			RTPpacket rtp_packet = mediastack.get(0);

			// get the payload bitstream from the RTPpacket object
			int payload_length = rtp_packet.getpayload_length();
			byte[] payload = new byte[payload_length];
			rtp_packet.getpayload(payload);

			// remove the displayed package
			mediastack.remove(0);

			return payload; // Return next image as bytearray
		} else {
			return null; // No image to show
		}
	}
    
}