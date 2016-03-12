/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.cubes;

import java.io.IOException;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.control.AbstractControl;
import com.jme3.scene.control.Control;
import com.cubes.network.*;
import java.util.Calendar;
import java.util.HashMap;

/**
 *
 * @author Carl
 */
public class BlockChunkControl extends AbstractControl implements BitSerializable{

    public BlockChunkControl(BlockTerrainControl terrain, int x, int y, int z){
        this.terrain = terrain;
        location.set(x, y, z);
        int cX = terrain.getSettings().getChunkSizeX();
        int cY = terrain.getSettings().getChunkSizeY();
        int cZ = terrain.getSettings().getChunkSizeZ();
        if (cY > 256) {
            // to support more than 256, blocks on surface (and probobly other things) needs to be larger than byte
            throw new UnsupportedOperationException("Chunks taller than 256 are not supported");
        }
        blockLocation.set(location.mult(cX, cY, cZ));
        node.setLocalTranslation(new Vector3f(blockLocation.getX(), blockLocation.getY(), blockLocation.getZ()).mult(terrain.getSettings().getBlockSize()));
        blockTypes = new byte[cX][cY][cZ];
        blocks_IsOnSurface = new byte[cX][cZ];
        sunlight = terrain.getSettings().getSunlightLevel();
        if (terrain.getSettings().getLightsEnabled()) {
            lightSources = new byte[cX][cY][cZ];
            lightLevels = new byte[cX][cY][cZ];
        }
        for( int iX = 0; iX < cX; ++iX) {
            for (int iZ = 0; iZ < cZ; ++iZ) {
                blocks_IsOnSurface[iX][iZ] = 0;
            if (terrain.getSettings().getLightsEnabled()) {
                    for (int iY = 0; iY < cY; ++iY) {
                        lightSources[iX][iY][iZ] = 0;
                        lightLevels[iX][iY][iZ] = 0;
                    }
                }
            }
        }

    }
    private byte sunlight = 0;
    private BlockTerrainControl terrain;
    private Vector3Int location = new Vector3Int();
    private Vector3Int blockLocation = new Vector3Int();
    private byte[][][] blockTypes;
    private byte[][][] lightSources;
    private byte[][][] lightLevels;
    private byte[][] blocks_IsOnSurface;
    private Node node = new Node("Cube Chunk");
    private Geometry optimizedGeometry_Opaque;
    private Geometry optimizedGeometry_Transparent;
    private boolean needsMeshUpdate;

    @Override
    public void setSpatial(Spatial spatial){
        Spatial oldSpatial = this.spatial;
        super.setSpatial(spatial);
        if(spatial instanceof Node){
            Node parentNode = (Node) spatial;
            parentNode.attachChild(node);
        }
        else if(oldSpatial instanceof Node){
            Node oldNode = (Node) oldSpatial;
            oldNode.detachChild(node);
        }
    }

    @Override
    protected void controlUpdate(float lastTimePerFrame){
        
    }

    @Override
    protected void controlRender(RenderManager renderManager, ViewPort viewPort){
        
    }

    @Override
    public Control cloneForSpatial(Spatial spatial){
        throw new UnsupportedOperationException("Not supported yet.");
    }
    
    public Block getNeighborBlock_Local(Vector3Int location, Block.Face face){
        Vector3Int neighborLocation = BlockNavigator.getNeighborBlockLocalLocation(location, face);
        return getBlock(neighborLocation);
    }
    
    public Block getNeighborBlock_Global(Vector3Int location, Block.Face face){
        return terrain.getBlock(getNeighborBlockGlobalLocation(location, face));
    }
    
    public Vector3Int getNeighborBlockGlobalLocation(Vector3Int location, Block.Face face){
        Vector3Int neighborLocation = BlockNavigator.getNeighborBlockLocalLocation(location, face);
        neighborLocation.addLocal(blockLocation);
        return neighborLocation;
    }
    public Vector3Int getBlockGlobalLocation(Vector3Int location){
        Vector3Int neighborLocation = location.clone();
        neighborLocation.addLocal(blockLocation);
        return neighborLocation;
    }
    
    public Block getBlock(Vector3Int location){
        if(isValidBlockLocation(location)){
            byte blockType = blockTypes[location.getX()][location.getY()][location.getZ()];
            return BlockManager.getBlock(blockType);
        }
        return null;
    }
    
    public void setBlock(Vector3Int location, Block block){
        if(isValidBlockLocation(location)){
            byte blockType = BlockManager.getType(block);
            blockTypes[location.getX()][location.getY()][location.getZ()] = blockType;
            updateBlockState(location);
            needsMeshUpdate = true;
        }
    }
    
    public void removeBlock(Vector3Int location){
        if(isValidBlockLocation(location)){
            blockTypes[location.getX()][location.getY()][location.getZ()] = 0;
            updateBlockState(location);
            needsMeshUpdate = true;
        }
    }
    
    private boolean isValidBlockLocation(Vector3Int location){
        return Util.isValidIndex(blockTypes, location);
    }
    
    public boolean updateSpatial(){
        if(needsMeshUpdate){
            if(optimizedGeometry_Opaque == null){
                optimizedGeometry_Opaque = new Geometry("Cube optimized_opaque");
                optimizedGeometry_Opaque.setQueueBucket(Bucket.Opaque);
                node.attachChild(optimizedGeometry_Opaque);
                updateBlockMaterial();
            }
            if(optimizedGeometry_Transparent == null){
                optimizedGeometry_Transparent = new Geometry("Cube optimized_transparent");
                optimizedGeometry_Transparent.setQueueBucket(Bucket.Transparent);
                node.attachChild(optimizedGeometry_Transparent);
                updateBlockMaterial();
            }
            optimizedGeometry_Opaque.setMesh(BlockChunk_MeshOptimizer.generateOptimizedMesh(this, false));
            optimizedGeometry_Transparent.setMesh(BlockChunk_MeshOptimizer.generateOptimizedMesh(this, true));
            needsMeshUpdate = false;
            return true;
        }
        return false;
    }
    
    public void updateBlockMaterial(){
        if(optimizedGeometry_Opaque != null){
            optimizedGeometry_Opaque.setMaterial(terrain.getSettings().getBlockMaterial());
        }
        if(optimizedGeometry_Transparent != null){
            optimizedGeometry_Transparent.setMaterial(terrain.getSettings().getBlockMaterial());
        }
    }
    private void updateBlockState(Vector3Int location){
        HashMap<String, LightQueueElement> lightsToAdd = new HashMap<String, LightQueueElement> ();
        HashMap<String, LightQueueElement> lightsToRemove = new HashMap<String, LightQueueElement> ();
        
        updateBlockInformation(location, lightsToAdd, lightsToRemove);
        for(int i=0;i<Block.Face.values().length;i++){
            Vector3Int neighborLocation = getNeighborBlockGlobalLocation(location, Block.Face.values()[i]);
            BlockChunkControl chunk = terrain.getChunk(neighborLocation);
            if(chunk != null){
                chunk.updateBlockInformation(neighborLocation.subtract(chunk.getBlockLocation()), lightsToAdd, lightsToRemove);
                chunk.needsMeshUpdate = true;
            }
        }
        terrain.removeLightSource(lightsToRemove);
        terrain.addLightSource(lightsToAdd);
    }
    private void updateBlockInformation(Vector3Int location, HashMap<String, LightQueueElement> lightsToAdd, HashMap<String, LightQueueElement> lightsToRemove){
    //    Block neighborBlock_Top = terrain.getBlock(getNeighborBlockGlobalLocation(location, Block.Face.Top));
    //    if blocks_IsOnSurface[location.getX()][location.getZ()] = (neighborBlock_Top == null);
        Block block = getBlock(location);
        // If the block is set
        if (block != null) {
           
            if (terrain.getSettings().getLightsEnabled()) {
                lightsToRemove.put(BlockTerrainControl.keyify(terrain.getGlobalBlockLocation(location, this)), new LightQueueElement(location, this));
            }                    
           
            // if the block is higher up than the surface block
            if (location.getY() > blocks_IsOnSurface[location.getX()][location.getZ()]) {
 
                // then it is the new surface block
                if (terrain.getSettings().getLightsEnabled()) {
                    int searchY = location.getY() + 1;
                    for (; searchY < terrain.getSettings().getChunkSizeY(); ++searchY) {
                        Vector3Int searchLoc = new Vector3Int(location.getX(), searchY, location.getZ());
                        if (lightSources[location.getX()][searchY][location.getZ()] > 0) {
                            break;
                        }
                        lightsToAdd.put(terrain.keyify(terrain.getGlobalBlockLocation(searchLoc, this)), new LightQueueElement(searchLoc, this, sunlight));
                    }
                    searchY = location.getY();
                    for (; searchY > blocks_IsOnSurface[location.getX()][location.getZ()]; --searchY) {
                        Vector3Int searchLoc = new Vector3Int(location.getX(), searchY, location.getZ());
                        lightsToRemove.put(terrain.keyify(terrain.getGlobalBlockLocation(searchLoc, this)), new LightQueueElement(searchLoc, this));
                    }
                }
                blocks_IsOnSurface[location.getX()][location.getZ()] = (byte)location.getY();
            }
        }
        // else block is cleared
        else {
            // if the block used to be the surface block
            if (location.getY() == blocks_IsOnSurface[location.getX()][location.getZ()]) {
                // then find the next block down to be surface
                int searchY = location.getY();
                for (; searchY > 0; --searchY) {
                    Vector3Int searchLoc = new Vector3Int(location.getX(), searchY, location.getZ());
                    block = getBlock(searchLoc);
                    if (block != null) {
                        blocks_IsOnSurface[location.getX()][location.getZ()] = (byte)searchY;
                        break;
                    } else {
                        if (terrain.getSettings().getLightsEnabled()) {
                            lightsToAdd.put(terrain.keyify(terrain.getGlobalBlockLocation(searchLoc, this)), new LightQueueElement(searchLoc, this, sunlight));
                        }
                    }
                }
                if (0 == searchY) {
                    blocks_IsOnSurface[location.getX()][location.getZ()] = 0;    
                }    
            } else if (location.getY() >= blocks_IsOnSurface[location.getX()][location.getZ()]) {
                if (terrain.getSettings().getLightsEnabled()) {
                    lightsToAdd.put(terrain.keyify(terrain.getGlobalBlockLocation(location, this)), new LightQueueElement(location, this, sunlight));
                }
            } else {
                if (terrain.getSettings().getLightsEnabled()) {
                    byte brightestLight = 0;
                    if (lightLevels[location.getX()][location.getY()][location.getZ()] == 0) {
                        for(int i=0;i<Block.Face.values().length;i++){
                            Vector3Int neighborLocation = BlockNavigator.getNeighborBlockLocalLocation(location, Block.Face.values()[i]);
                            neighborLocation = terrain.getGlobalBlockLocation(neighborLocation, this);
                            byte neighborLight = terrain.getLightLevelOfBlock(neighborLocation);
                            brightestLight = (byte)Math.max((int)neighborLight, (int)brightestLight);
                        }
                        if ( brightestLight > 1 ) {
                            lightsToAdd.put(terrain.keyify(terrain.getGlobalBlockLocation(location, this)), new LightQueueElement(location, this, (byte)(brightestLight - 1), false));
                        }
                    }
                }
            }
        }
    }

    public boolean isBlockOnSurface(Vector3Int location){
        return blocks_IsOnSurface[location.getX()][location.getZ()] == (byte)location.getY();
    }
    
    public boolean isBlockAboveSurface(Vector3Int location) {
        if (location.getX() >= 0 && location.getX() <= blocks_IsOnSurface.length &&
            location.getZ() >= 0 && location.getZ() <= blocks_IsOnSurface[0].length) {
            return blocks_IsOnSurface[location.getX()][location.getZ()] <= (byte)location.getY();
        } else {
            return false;
        }
    }

    public BlockTerrainControl getTerrain(){
        return terrain;
    }

    public Vector3Int getLocation(){
        return location;
    }

    public Vector3Int getBlockLocation(){
        return blockLocation;
    }

    public Node getNode(){
        return node;
    }

    public Geometry getOptimizedGeometry_Opaque(){
        return optimizedGeometry_Opaque;
    }

    public Geometry getOptimizedGeometry_Transparent(){
        return optimizedGeometry_Transparent;
    }

    @Override
    public void write(BitOutputStream outputStream){
        for(int x=0;x<blockTypes.length;x++){
            for(int y=0;y<blockTypes[0].length;y++){
                for(int z=0;z<blockTypes[0][0].length;z++){
                    outputStream.writeBits(blockTypes[x][y][z], 8);
                }
            }
        }
    }

    public void write(int sliceIndex, BitOutputStream outputStream){
        for(int x=0;x<blockTypes.length;x++){
            for(int z=0;z<blockTypes[0][0].length;z++){
                outputStream.writeBits(blockTypes[x][sliceIndex][z], 8);
            }
        }
    }
    
    @Override
    public void read(BitInputStream inputStream) throws IOException{
        for(int x=0;x<blockTypes.length;x++){
            for(int y=0;y<blockTypes[0].length;y++){
                for(int z=0;z<blockTypes[0][0].length;z++){
                    blockTypes[x][y][z] = (byte) inputStream.readBits(8);
                }
            }
        }
        Vector3Int tmpLocation = new Vector3Int();
        HashMap<String, LightQueueElement> lightsToAdd = new HashMap<String, LightQueueElement> ();
        HashMap<String, LightQueueElement> lightsToRemove = new HashMap<String, LightQueueElement> ();
        for(int x=0;x<blockTypes.length;x++){
            for(int y=0;y<blockTypes[0].length;y++){
                for(int z=0;z<blockTypes[0][0].length;z++){
                    tmpLocation.set(x, y, z);
                    updateBlockInformation(tmpLocation, lightsToAdd, lightsToRemove);
                }
            }
        }
        terrain.removeLightSource(lightsToRemove);
        terrain.addLightSource(lightsToAdd);
        needsMeshUpdate = true;
    }
     public void read(int slice, BitInputStream inputStream) throws IOException{
        for(int x=0;x<blockTypes.length;x++){
            for(int z=0;z<blockTypes[0][0].length;z++){
                blockTypes[x][slice][z] = (byte) inputStream.readBits(8);
            }
        }
        HashMap<String, LightQueueElement> lightsToAdd = new HashMap<String, LightQueueElement> ();
        HashMap<String, LightQueueElement> lightsToRemove = new HashMap<String, LightQueueElement> ();
        Vector3Int tmpLocation = new Vector3Int();
        long startTime = Calendar.getInstance().getTimeInMillis();
        long endTime;
        
        for(int x=0;x<blockTypes.length;x++){
            for(int z=0;z<blockTypes[0][0].length;z++){
                tmpLocation.set(x, slice, z);
                updateBlockInformation(tmpLocation, lightsToAdd, lightsToRemove);
            }
        }
        endTime = Calendar.getInstance().getTimeInMillis();
        if (endTime - startTime > 2) {
            System.err.println("update block info took " + (endTime - startTime) + "ms");
        }
        startTime = Calendar.getInstance().getTimeInMillis();
        terrain.removeLightSource(lightsToRemove);
        endTime = Calendar.getInstance().getTimeInMillis();
        if (endTime - startTime > 2) {
            System.err.println("removing lights took " + (endTime - startTime) + "ms");
        }
        startTime = Calendar.getInstance().getTimeInMillis();
        terrain.addLightSource(lightsToAdd);
        endTime = Calendar.getInstance().getTimeInMillis();
        if (endTime - startTime > 2) {
            System.err.println("putting lights took " + (endTime - startTime) + "ms");
        }
        needsMeshUpdate = true;
    }
    
    private Vector3Int getNeededBlockChunks(Vector3Int blocksCount){
        int chunksCountX = (int) Math.ceil(((float) blocksCount.getX()) / terrain.getSettings().getChunkSizeX());
        int chunksCountY = (int) Math.ceil(((float) blocksCount.getY()) / terrain.getSettings().getChunkSizeY());
        int chunksCountZ = (int) Math.ceil(((float) blocksCount.getZ()) / terrain.getSettings().getChunkSizeZ());
        return new Vector3Int(chunksCountX, chunksCountY, chunksCountZ);
    }

    boolean addLightSource(Vector3Int localBlockLocation, byte brightness) {
        if (brightness == 0 || lightSources[localBlockLocation.getX()][localBlockLocation.getY()][localBlockLocation.getZ()] < brightness) {
            lightSources[localBlockLocation.getX()][localBlockLocation.getY()][localBlockLocation.getZ()] = brightness;
            if (brightness == 0) {
                lightLevels[localBlockLocation.getX()][localBlockLocation.getY()][localBlockLocation.getZ()] = 0;
            }
            return true;
        }
        return false;
    }

    boolean propigateLight(Vector3Int localBlockLocation, byte brightness) {
        if (brightness < 0) {
            return false;
        }
        if (getBlock(localBlockLocation) != null) {
            lightLevels[localBlockLocation.getX()][localBlockLocation.getY()][localBlockLocation.getZ()] = 0;
            needsMeshUpdate = true;
            return false;
        }
        byte oldLightLevel = lightLevels[localBlockLocation.getX()][localBlockLocation.getY()][localBlockLocation.getZ()];
        if (oldLightLevel < brightness) {
            lightLevels[localBlockLocation.getX()][localBlockLocation.getY()][localBlockLocation.getZ()] = brightness;
            needsMeshUpdate = true;
            return true;
        }
        return false;
    }

    boolean propigateDark(Vector3Int localBlockLocation, byte oldLight) {
        if (lightLevels[localBlockLocation.getX()][localBlockLocation.getY()][localBlockLocation.getZ()] == 0) {
            return false;
        }
        if (lightLevels[localBlockLocation.getX()][localBlockLocation.getY()][localBlockLocation.getZ()] <= oldLight) {
            lightLevels[localBlockLocation.getX()][localBlockLocation.getY()][localBlockLocation.getZ()] = 0;
            needsMeshUpdate = true;
            return true;
        }
        return false;
    }

    byte getLightSourceAt(Vector3Int localBlockLocation) {
        if (localBlockLocation.getX() > 15 || localBlockLocation.getZ() > 15) {
            System.out.println("out of bounds");
            return 0;
        }
        return lightSources[localBlockLocation.getX()][localBlockLocation.getY()][localBlockLocation.getZ()];
    }    

    byte getLightAt(Vector3Int localBlockLocation) {
        return lightLevels[localBlockLocation.getX()][localBlockLocation.getY()][localBlockLocation.getZ()];
    }

}
