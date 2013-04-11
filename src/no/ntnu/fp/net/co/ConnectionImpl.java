/*
 * Created on Oct 27, 2004
 */
package no.ntnu.fp.net.co;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

//import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import no.ntnu.fp.net.admin.Log;
import no.ntnu.fp.net.cl.ClException;
import no.ntnu.fp.net.cl.ClSocket;
import no.ntnu.fp.net.cl.KtnDatagram;
import no.ntnu.fp.net.cl.KtnDatagram.Flag;

/**
 * Implementation of the Connection-interface. <br>
 * <br>
 * This class implements the behavior in the methods specified in the interface
 * {@link Connection} over the unreliable, connectionless network realized in
 * {@link ClSocket}. The base class, {@link AbstractConnection} implements some
 * of the functionality, leaving message passing and error handling to this
 * implementation.
 * 
 * @author Sebjørn Birkeland and Stein Jakob Nordbø
 * @see no.ntnu.fp.net.co.Connection
 * @see no.ntnu.fp.net.cl.ClSocket
 */
public class ConnectionImpl extends AbstractConnection {

    /** Keeps track of the used ports for each server port. */
    private static Map<Integer, Boolean> usedPorts = Collections.synchronizedMap(new HashMap<Integer, Boolean>());

    /**
     * Initialize initial sequence number and setup state machine.
     * 
     * @param myPort
     *            - the local port to associate with this connection
     */
    public ConnectionImpl(int myPort) {
    	super();
        this.myPort = myPort;
        usedPorts.put(myPort, true);
        myAddress = getIPv4Address();
        //throw new NotImplementedException();
    }

    private String getIPv4Address() {
    	try {
            return InetAddress.getLocalHost().getHostAddress();
        }
        catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    /**
     * Establish a connection to a remote location.
     * 
     * @param remoteAddress
     *            - the remote IP-address to connect to
     * @param remotePort
     *            - the remote portnumber to connect to
     * @throws IOException
     *             If there's an I/O error.
     * @throws java.net.SocketTimeoutException
     *             If timeout expires before connection is completed.
     * @see Connection#connect(InetAddress, int)
     */
    public void connect(InetAddress remoteAddress, int remotePort) throws IOException,
            SocketTimeoutException {
    	this.remotePort = remotePort;
        this.remoteAddress = remoteAddress.getHostAddress();
        
        try {
        	//Send SYN
        	System.out.println("send syn");
        	KtnDatagram ktnConnect = constructInternalPacket(Flag.SYN);
        	simplySendPacket(ktnConnect);
			state = State.SYN_SENT;

			//Receive SYNACK
			System.out.println("receive synack");
			KtnDatagram ktnSynAck = receiveAck();
			if (!isValid(ktnSynAck)) {
				throw new IOException();
			}
			System.out.println("ack nr: " + ktnSynAck.getSeq_nr());

			//Fix stupid problem
			Thread.sleep(10);

			//Send ACK
			System.out.println("send ack");
			sendAck(ktnSynAck, false);
			state = State.ESTABLISHED;

			System.out.println("connection established");

		} catch (Exception e) {
			state = State.CLOSED;
			e.printStackTrace();
			throw new IOException();
		}
    }

    /**
     * Listen for, and accept, incoming connections.
     * 
     * @return A new ConnectionImpl-object representing the new connection.
     * @see Connection#accept()
     */
    public Connection accept() throws IOException, SocketTimeoutException {
		state = State.LISTEN;
        
        try {
	        //Receive SYN
        	System.out.println("wait for syn");
	        KtnDatagram ktnAccept = receivePacket(true);
	        if (!isValid(ktnAccept)) {
	        	System.out.println("not valid");
	        	throw new IOException();
	        }
	        remoteAddress= ktnAccept.getSrc_addr();
	        remotePort = ktnAccept.getSrc_port();

	        state = State.SYN_RCVD;

	        System.out.println(ktnAccept.getSeq_nr());

	        //Send SYNACK
	        System.out.println("send synack");
	        sendAck(ktnAccept, true);

	        //Receive ACK
	        System.out.println("wait for ack");
	        KtnDatagram ktnAck = receiveAck();
	        if (!isValid(ktnAck)) {
	        	throw new IOException();
	        }
	        System.out.println(ktnAck.getFlag());
	        state = State.CLOSED;

	        //Return connection
	        System.out.println("connection established");
	        ConnectionImpl con = new ConnectionImpl(myPort);
	        con.nextSequenceNo = nextSequenceNo;
	        con.myAddress = myAddress;
	        con.myPort = myPort;
	        con.remoteAddress = ktnAccept.getSrc_addr();
	        con.remotePort = ktnAccept.getSrc_port();
	        con.state = State.ESTABLISHED;
	        return con;
        } catch (Exception e) {
        	state = State.CLOSED;
        	e.printStackTrace();
        	throw new IOException();
        }
    }

    /**
     * Send a message from the application.
     * 
     * @param msg
     *            - the String to be sent.
     * @throws ConnectException
     *             If no connection exists.
     * @throws IOException
     *             If no ACK was received.
     * @see AbstractConnection#sendDataPacketWithRetransmit(KtnDatagram)
     * @see no.ntnu.fp.net.co.Connection#send(String)
     */
    public void send(String msg) throws ConnectException, IOException {
    	if(state != State.ESTABLISHED){
        	throw new ConnectException();
        }
        
        KtnDatagram ktnPacket = constructDataPacket(msg);
        KtnDatagram ktnAck = sendDataPacketWithRetransmit(ktnPacket);
        
        while (ktnAck != null && !isValid(ktnAck)) {
        	ktnAck = sendDataPacketWithRetransmit(ktnPacket);
        }
        
        if (ktnAck == null) {
        	throw new IOException("No ack received");
        }
    }

    /**
     * Wait for incoming data.
     * 
     * @return The received data's payload as a String.
     * @see Connection#receive()
     * @see AbstractConnection#receivePacket(boolean)
     * @see AbstractConnection#sendAck(KtnDatagram, boolean)
     */
    public String receive() throws ConnectException, IOException {
    	if(state != State.ESTABLISHED){
        	throw new ConnectException();
        }
    	
        KtnDatagram ktnReceivePack = receivePacket(false);
        while (!isValid(ktnReceivePack)) {
        	
        	if(state != State.ESTABLISHED){
            	throw new ConnectException();
            }
        	
        	nextSequenceNo--;
        	sendAck(lastValidPacketReceived, false);
        	ktnReceivePack = receivePacket(false);
        }
        
        sendAck(lastValidPacketReceived, false);
		return ktnReceivePack.toString();
        
    }

    /**
     * Close the connection.
     * 
     * @see Connection#close()
     */
    public void close() throws IOException {
    	try {
    		if (lastValidPacketReceived.getFlag() == Flag.FIN) {
    			System.out.println("> alternativ 1");
    			
    			//Fix stupid problem
    			Thread.sleep(10);
    			
    			//Send ack
    			sendAck(lastValidPacketReceived, false);
    			
    			//Fix stupid problem
    			Thread.sleep(100);
    			
    			//Send fin
    			state = State.CLOSE_WAIT;
		    	KtnDatagram finPacket = constructInternalPacket(Flag.FIN);
		    	simplySendPacket(finPacket);
		    	//sendDataPacketWithRetransmit(finPacket);

		    	//Wait for ACK
		    	state = State.LAST_ACK;
		    	KtnDatagram ktnFinAck = receiveAck();
		    	if (ktnFinAck == null) {
		    		throw new Exception("No packet received");
		    	}
		    	if (!isValid(ktnFinAck)) {
					throw new IOException("Invalid packet");
				}

    		}
    		else {
    			System.out.println("> alternativ 2");
		    	//Send fin
		    	KtnDatagram finPacket = constructInternalPacket(Flag.FIN);
		    	simplySendPacket(finPacket);
		    	//sendDataPacketWithRetransmit(finPacket);

		    	//Wait for ACK
		    	state = State.FIN_WAIT_1;
		    	KtnDatagram ktnFinAck = receiveAck();
		    	if (ktnFinAck == null) {
		    		throw new Exception("No packet received");
		    	}
		    	if (!isValid(ktnFinAck)) {
					throw new IOException();
				}

		    	//Wait for fin
		    	state = State.FIN_WAIT_2;
		    	KtnDatagram ktnFin = receivePacket(true);
		    	if (ktnFin == null) {
		    		throw new Exception("No packet received");
		    	}
		    	if (!isValid(ktnFin)) {
					throw new IOException("Invalid packet");
				}

		    	//Fix stupid problem
    			Thread.sleep(10);

		    	//Send ack
		    	sendAck(ktnFin, false);
    		}
    	}
    	catch (Exception e) {
    		e.printStackTrace();
    		//throw new IOException();
    	}
        
        //Closed
        state = State.CLOSED;
    }

    /**
     * Test a packet for transmission errors. This function should only called
     * with data or ACK packets in the ESTABLISHED state.
     * 
     * @param packet
     *            Packet to test.
     * @return true if packet is free of errors, false otherwise.
     */
    protected boolean isValid(KtnDatagram packet) {
System.out.println("> start validate");
    	
    	Flag flag = packet.getFlag();
    	
        if (state == State.SYN_SENT && flag != Flag.SYN_ACK) {
        	return false;
        }
        if (state == State.SYN_RCVD && flag != Flag.ACK) {
        	return false;
        }
        if (state == State.LISTEN && flag != Flag.SYN) {
        	return false;
        }
        if (state == State.FIN_WAIT_1 && flag != Flag.ACK) {
        	return false;
        }
        if (state == State.FIN_WAIT_2 && flag != Flag.FIN) {
        	return false;
        }
        if (state == State.ESTABLISHED && (flag == Flag.SYN || flag == Flag.SYN_ACK)) {
        	return false;
        }
        if (state == State.LAST_ACK && flag != Flag.ACK) {
        	return false;
        }
        
        if (packet.getChecksum() != packet.calculateChecksum()) {
        	return false;
        }
        
        if (flag == Flag.ACK && packet.getAck() != nextSequenceNo - 1) {
        	return false;
        }
        
        if (lastValidPacketReceived != null && packet.getSeq_nr() != lastValidPacketReceived.getSeq_nr() + 1) {
        	System.out.println(packet.getSeq_nr());
        	System.out.println(lastValidPacketReceived.getSeq_nr());
        	return false;
        }
        
        System.out.println("> valid: " + packet.getSeq_nr());
        lastValidPacketReceived = packet;
        return true;
    }
}
