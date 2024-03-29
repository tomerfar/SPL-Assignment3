package bgu.spl.net.impl.tftp;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import bgu.spl.net.api.BidiMessagingProtocol;
import bgu.spl.net.srv.ConnectionsImpl;


public class TftpProtocol implements BidiMessagingProtocol<byte[]>  {

    private int connectionId;
    private ConnectionsImpl<byte[]> connections;
    private boolean shouldTerminate;
    private short opCode;
    private byte[] uploadFile;
    private int blocksSent;
    private String uploadingFileName;
    private int expectedBlocks;
    private boolean loggedIn;
    private byte[] sendingFile;
    private int pos;
    private short block;
    private String filesPath;

    //OpCode fields
    short op_RRQ = 1; short op_WRQ = 2; short op_DATA = 3; short op_ACK = 4; short op_ERROR = 5;
    short op_DIRQ = 6; short op_LOGRQ = 7; short op_DELRQ = 8; short op_BCAST = 9; short op_DISC = 10;
    
    @Override
    public void start(int _connectionId, ConnectionsImpl<byte[]> _connections) {
        // TODO implement this

        connectionId = _connectionId;
        connections = _connections;
        shouldTerminate = false;
        uploadFile = new byte[1<<10];
        blocksSent = 0;
        expectedBlocks = 0;
        loggedIn = false;
        pos = 0;
        block = 1;
        Path p = Paths.get("Files");
        File dir = p.toFile();
        filesPath = dir.getAbsolutePath();
    }

    @Override
    public void process(byte[] message){
        // TODO implement this

        opCode = (short)(((short)message[0] & 0xFF)<<8|(short)(message[1] & 0xFF)); 
        if(opCode == op_LOGRQ){
            String username = new String(message, 2, message.length - 3, StandardCharsets.UTF_8);
            loggedIn = holder.ids_login.containsValue(username) || holder.ids_login.get(connectionId) != null;
            if(!loggedIn) { // if username does not exist 
                holder.ids_login.put(connectionId, username); 
                loggedIn = true;
                byte[] msgACK = packAck((short)0);
                connections.send(connectionId, msgACK);
            } else {
                String error = "User already logged in" + '\0';
                byte[] msgERROR = packError(error);
                connections.send(connectionId, msgERROR);
            }
        }
        else if ( 10 < opCode | 1 > opCode ){
            String error = "Illegal TFTP Operation - Unknown Opcode" + '\0';
            byte[] msgERROR = packError(error);
            connections.send(connectionId, msgERROR);
        }
        else if(!loggedIn){
            String error = "User not logged in" + '\0';
            byte[] msgERROR = packError(error);
            connections.send(connectionId, msgERROR);
        }
        else if(opCode == op_ACK){
            short blockNum = (short)(((short)message[2] & 0xFF)<<8|(short)(message[3] & 0xFF));
            if (blockNum > 0) { // if a data packet was acknowledged (from RRQ or DIRQ)
                if(pos < sendingFile.length) {
                    sendNextPack();
                } else { // all of the content was sent
                    block = 1;
                    pos = 0;
                }
            }
            
        }
        else if(opCode == op_DATA){
            short packSize = (short)(((short)message[2] & 0xFF)<<8|(short)(message[3] & 0xFF));
            short blockNum = (short)(((short)message[4] & 0xFF)<<8|(short)(message[5] & 0xFF));
            byte[] data = Arrays.copyOfRange(message, 6, message.length - 1);

            if (packSize < 512){
                expectedBlocks = blockNum;
            }

            if (packSize + blocksSent*512 > uploadFile.length) { // in case uploadFile array is not big enough
                byte[] temp = new byte[uploadFile.length*2];
                System.arraycopy(uploadFile, 0, temp, 0, uploadFile.length);
                uploadFile = temp;
            }

            // add data to uploadFile
            System.arraycopy(data, 0, uploadFile, blocksSent*512, packSize);
            blocksSent++;

            // send ACK that data packet was received 
            byte[] msgACK = packAck((short)blocksSent);
            connections.send(connectionId, msgACK);

            // if all the blocks were sent 
            if (expectedBlocks == blocksSent){
                // create new file in Files folder
                String pathName = filesPath + File.separator + uploadingFileName;
                File f = new File(pathName);
                f.getParentFile().mkdirs(); 
                try {
                    f.createNewFile();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                // add content to new file 
                try (FileOutputStream fos = new FileOutputStream(pathName)) {
                    fos.write(uploadFile);
                    fos.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                holder.filesMap.put(uploadingFileName, f);
                uploadFile = new byte[1<<10];
                blocksSent = 0;
                expectedBlocks = 0;
                byte[] filenameBytes = uploadingFileName.getBytes();
                byte[] msgBCAST = new byte[4 + filenameBytes.length];
                msgBCAST[0] = (byte) (op_BCAST >> 8);
                msgBCAST[1] = (byte) (op_BCAST & 0xff);
                msgBCAST[2] = 1;
                msgBCAST[msgBCAST.length -1] = (byte) 0;
                System.arraycopy(filenameBytes, 0, msgBCAST, 3, filenameBytes.length);
                for(Integer id : holder.ids_login.keySet()){
                    connections.send(id, msgBCAST); // sends the BCAST to all login clients
                }
            }
        }
        else if(opCode == op_DELRQ) {
            String filename = new String(message, 2, message.length - 3, StandardCharsets.UTF_8); 
            if(holder.filesMap.containsKey(filename)){ 
                File f = holder.filesMap.remove(filename);
                try {
                    Files.delete(f.toPath());
                } catch (NoSuchFileException x) {
                    System.err.format("%s: no such" + " file or directory%n", f.toPath());
                } catch (DirectoryNotEmptyException x) {
                    System.err.format("%s not empty%n", f.toPath());
                } catch (IOException x) {
                    // File permission problems are caught here.
                    System.err.println(x);
                }
                byte[] msgACK = packAck((short)0);
                connections.send(connectionId, msgACK); // send ack to client that sent request 

                byte[] filenameBytes = filename.getBytes(); 
                byte[] msgBCAST = new byte[4 + filename.length()];
                msgBCAST[0] = (byte) (op_BCAST >> 8);
                msgBCAST[1] = (byte) (op_BCAST & 0xff);
                msgBCAST[2] = 0;
                msgBCAST[msgBCAST.length -1] = (byte) 0;
                System.arraycopy(filenameBytes, 0, msgBCAST, 3, filenameBytes.length);
                for(Integer id : holder.ids_login.keySet()){
                    connections.send(id, msgBCAST); // sends the BCAST to all login clients
                }
            } else {
                String error = filename +  " not found" + '\0'; 
                byte[] msgERROR = packError(error);
                connections.send(connectionId, msgERROR);
            }  
        }
        else if(opCode == op_DIRQ){
            if(!holder.filesMap.isEmpty()){
                // iterate on hashmap
                String filesList = new String();
                for(String filename : holder.filesMap.keySet()){
                    filesList+= filename.concat("\n");
                }

                sendingFile = filesList.substring(0, filesList.length()-1).getBytes();
                sendNextPack();
            } else {
                String error = "Nothing." + '\0';
                byte[] msgERROR = packError(error);
                connections.send(connectionId, msgERROR);
            }
        }
        else if(opCode == op_RRQ){ //Download
            String filename = new String(message, 2, message.length - 3, StandardCharsets.UTF_8);

            if(holder.filesMap.containsKey(filename)){
                File f = holder.filesMap.get(filename);
                try {
                    sendingFile = Files.readAllBytes(f.toPath());
                } catch (IOException e) {
                    e.printStackTrace();
                }
                sendNextPack();
            } else {
                String error = "deleted created file." + '\0';
                byte[] msgERROR = packError(error);
                connections.send(connectionId, msgERROR);
            }
        }
        else if(opCode == op_WRQ){ //Upload
            String filename = new String(message, 2, message.length - 3, StandardCharsets.UTF_8);
            if(!holder.filesMap.containsKey(filename)){
                byte[] msgACK = packAck((short) 0);
                connections.send(connectionId, msgACK);
                uploadingFileName = filename;
            } else {
                String error = "stop transfer." + '\0';
                byte[] msgERROR = packError(error); 
                connections.send(connectionId, msgERROR);
            }
        }
        else if(opCode == op_DISC) {  
            loggedIn = holder.ids_login.get(connectionId) != null;
            if(loggedIn){ // check if this condition is right 
                byte[] msgACK = packAck((short) 0);
                shouldTerminate = true;
                connections.send(connectionId, msgACK);
                connections.disconnect(connectionId);
            } else { // check this 
                String error = "Don't exit the program." + '\0';
                byte[] msgERROR = packError(error); 
                connections.send(connectionId, msgERROR);
            }   
        } 
    }

    @Override
    public boolean shouldTerminate() {
       return shouldTerminate;
    }


    private byte[] packError(String error) {
        byte[] errorByte = error.getBytes(); //uses utf8 by default
        byte[] msgERROR = new byte[4 + errorByte.length];
        msgERROR[0] = (byte) (op_ERROR >> 8);
        msgERROR[1] = (byte) (op_ERROR & 0xff);
        msgERROR[2] = (byte) (opCode >> 8);
        msgERROR[3] = (byte) (opCode & 0xff);
        System.arraycopy(errorByte, 0, msgERROR, 4 , errorByte.length); 
        return msgERROR;
    }

    private byte[] packAck(short blockNum) {
        byte[] msgACK = new byte[4];
        msgACK[0] = (byte) (op_ACK >> 8);
        msgACK[1] = (byte) (op_ACK & 0xff);
        msgACK[2] = (byte) (blockNum >> 8 );
        msgACK[3] = (byte) (blockNum & 0xff);
        return msgACK;
    }

  


    private void sendNextPack() {
        short packetSize;

        if (pos + 512 < sendingFile.length) {
            packetSize = 512;
        } else {
            packetSize =  (short) (sendingFile.length - pos);
        }

        byte[] msgDATA = new byte[6 + packetSize];
        msgDATA[0] = (byte) (op_DATA >> 8);
        msgDATA[1] = (byte) (op_DATA & 0xff);
        msgDATA[2] = (byte) (packetSize >> 8);
        msgDATA[3] = (byte) (packetSize & 0xff);
        msgDATA[4] = (byte) (block >> 8);
        msgDATA[5] = (byte) (block & 0xff);
        System.arraycopy(sendingFile, pos, msgDATA, 6 , packetSize); 
        connections.send(connectionId, msgDATA);
        pos += 512;
        block++;
    }
}



