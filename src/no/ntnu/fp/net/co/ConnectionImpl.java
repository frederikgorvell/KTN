package no.ntnu.fp.net.co;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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
    private static boolean sendingPacket;
    private final int MAX_TRIES = 10;

    /**
     * Initialize initial sequence number and setup state machine.
     * 
     * @param myPort
     *            - the local port to associate with this connection
     */
    public ConnectionImpl(int myPort) {
    	super();
    	ConnectionImpl.usedPorts.put(myPort, true);
        this.myAddress = getIPv4Address();
        this.myPort = myPort;
    }

    public String getIPv4Address() {
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
    public void connect(InetAddress remoteAddress, int remotePort) throws IOException, SocketTimeoutException {
    	if (state != State.CLOSED){
    		throw new IllegalStateException("Must be in closed state.");
    	}
    	this.remoteAddress = remoteAddress.getHostAddress();
        this.remotePort = remotePort;
        KtnDatagram syn = constructInternalPacket(Flag.SYN);
        
        try{
			simplySendPacket(syn);
		}catch(ClException e){
			try{
				Thread.sleep(5000);
			}catch(InterruptedException ie){
			}
			this.connect(remoteAddress, remotePort);
			return;
		}

		state = State.SYN_SENT;

        KtnDatagram synack = receiveAck();
        
        if(!isValid(synack)){
        	throw new IOException("Not valid synack received.");
        }
        this.remotePort = synack.getSrc_port();
        lastValidPacketReceived = synack;
        
        try{
        	Thread.sleep(1000);
        }catch(InterruptedException e){
        }
        sendAck(synack, false);
        state = State.ESTABLISHED;
    }

    /**
     * Listen for, and accept, incoming connections.
     * 
     * @return A new ConnectionImpl-object representing the new connection.
     * @see Connection#accept()
     */
    public Connection accept() throws IOException, SocketTimeoutException {
    	if (state != State.CLOSED){
    		throw new IllegalStateException("Must be in closed state.");
    	}
    	state = State.LISTEN;
    	
    	KtnDatagram syn;
    	do {
    		syn = receivePacket(true);
    	} while (syn == null || syn.getFlag() != Flag.SYN);
    	
    	int port = 4000;
    	while (ConnectionImpl.usedPorts.containsKey(port)){
    		port++;
    	}
    	
    	ConnectionImpl newConnection = new ConnectionImpl(port);
    	newConnection.remoteAddress = syn.getSrc_addr();
    	newConnection.remotePort = syn.getSrc_port();
    	newConnection.state = State.SYN_RCVD;
    	
    	try{
    		Thread.sleep(1000);
    	}catch(InterruptedException e){
    	}
    	newConnection.sendAck(syn, true);
        
        KtnDatagram ack = newConnection.receiveAck();
        if(!isValid(ack)){
        	throw new IOException("Not valid ack received.");
        }
        newConnection.lastValidPacketReceived = ack;
        newConnection.state = State.ESTABLISHED;
        state = State.CLOSED;
        return newConnection;
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
    	while(sendingPacket){
			try{
				Thread.sleep(50);
			}catch(InterruptedException e){
			}
    	}

    	sendingPacket = true;
    	KtnDatagram packet = constructDataPacket(msg);
    	int triesLeft = MAX_TRIES;
    	KtnDatagram ack;
    	do{
    		ack = sendDataPacketWithRetransmit(packet);
    		if(ack != null){
    			System.out.println("\n sendPacket  " + packet.getSeq_nr() + " " + ack.getAck());
    		}
    	}while((!isValid(ack) || ack.getFlag() != Flag.ACK || ack.getAck() < packet.getSeq_nr()) && triesLeft-- > 0);
    	
    	if (ack == null) {
    		System.out.println("\n\nReceived no ack.\n\n");
    		return;
    	}
    	System.out.println("\n validPacketSent  " + lastValidPacketReceived.getSeq_nr() + " " + ack.getSeq_nr());
    	if (ack.getSeq_nr() > lastValidPacketReceived.getSeq_nr()){
    		lastValidPacketReceived = ack;
    	}
    	lastDataPacketSent = packet;
    	sendingPacket = false;
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
    	int triesLeft = MAX_TRIES;
    	KtnDatagram packet = null;
    	while (triesLeft-- > 0) {
	    	try{
	    		packet = receivePacket(false);
	    	}catch(EOFException e){
	    		if(disconnectRequest != null){
	        		sendAck(disconnectRequest, false);
	        		state = State.CLOSE_WAIT;
	        		throw e;
	    		}else{
	    			sendAck(lastValidPacketReceived, false);
	    			continue;
	    		}
	    	}
	    	if(packet.getFlag() == Flag.NONE && isValid(packet) && packet.getSeq_nr() == lastValidPacketReceived.getSeq_nr()+1){
	    		if(packet.getSeq_nr() > lastValidPacketReceived.getSeq_nr()){
	    			lastValidPacketReceived = packet;
	    			sendAck(packet, false);
	    		}else{
	    			sendAck(lastValidPacketReceived, false);
	    		}
	    		System.out.println("\n validPacket  " + lastValidPacketReceived.getSeq_nr() + " " + packet.getSeq_nr());
				return packet.toString();
	    	}
	    	System.out.println("\n sendAckAgain  " + lastValidPacketReceived.getSeq_nr() + " " + packet.getSeq_nr());
	    	sendAck(lastValidPacketReceived, false);
    	}
    	if(packet != null){
    		lastValidPacketReceived = packet; 
    		return packet.toString();
    	}else{
    		return receive();
    	}
    }

    /**
     * Close the connection.
     * 
     * @see Connection#close()
     */
    public void close() throws IOException {
    	State initialState = state;
        KtnDatagram packet = constructInternalPacket(Flag.FIN);
    	KtnDatagram ack;
    	do{
    		state = initialState;
    		try{
				simplySendPacket(packet);
			}catch(ClException e){
			}
			state = disconnectRequest != null ? State.LAST_ACK : State.FIN_WAIT_1;
    		ack = receiveAck();
    	}while(!isValid(ack) || ack.getFlag() != Flag.ACK);
    	if(ack.getSeq_nr() > lastValidPacketReceived.getSeq_nr()){
    		lastValidPacketReceived = ack;
    	}
        
        if(disconnectRequest != null){
        	state = State.CLOSED;
        }else{
        	state = State.FIN_WAIT_2;
        	KtnDatagram finPacket;
        	do{
        		finPacket = receivePacket(true);
        	}while (!isValid(finPacket));
        	try{
        		Thread.sleep(1000);
    		}catch(InterruptedException e){
    		}
        	sendAck(finPacket, false);
        	state = State.TIME_WAIT;
        	state = State.CLOSED;
        }
        if (ConnectionImpl.usedPorts.containsKey(myPort)){
        	ConnectionImpl.usedPorts.remove(myPort);
        }
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
        return packet != null && packet.getChecksum() == packet.calculateChecksum();
    }
    
    
    // Ugly hack to catch random SocketExceptions (not sure if it works...)
    @Override
    protected void sendAck(KtnDatagram ack, boolean synAck) throws IOException {
    	try {
    		super.sendAck(ack, synAck);
    	} catch (SocketException e) {
    		sendAck(ack, synAck);
    	}
    }
}