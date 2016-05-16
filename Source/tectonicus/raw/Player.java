/*
 * Copyright (c) 2012-2016, John Campbell and other contributors.  All rights reserved.
 *
 * This file is part of Tectonicus. It is subject to the license terms in the LICENSE file found in
 * the top-level directory of this distribution.  The full list of project contributors is contained
 * in the AUTHORS file found in the same location.
 *
 */

package tectonicus.raw;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

import javax.xml.bind.DatatypeConverter;

import org.jnbt.ByteTag;
import org.jnbt.CompoundTag;
import org.jnbt.DoubleTag;
import org.jnbt.IntTag;
import org.jnbt.ListTag;
import org.jnbt.NBTInputStream;
import org.jnbt.ShortTag;
import org.jnbt.Tag;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import tectonicus.configuration.Configuration.Dimension;
import tectonicus.util.Vector3d;
import tectonicus.util.Vector3l;

public class Player
{
	public static final int MAX_HEALTH = 20;
	public static final int MAX_AIR = 300;
	
	private String name;
	private String UUID;
	private String skinURL;
	
	private Dimension dimension;
	
	private Vector3d position;
	
	private Vector3l spawnPos;
	
	private int health; // 0-20
	private int food; // 0-20
	private int air; // 0-300
	
	private int xpLevel;
	private int xpTotal;
	
	private ArrayList<Item> inventory;
	
	public Player(File playerFile) throws Exception
	{
		System.out.println("Loading raw player from "+playerFile.getAbsolutePath());
		
		dimension = Dimension.Terra;
		position = new Vector3d();
		inventory = new ArrayList<Item>();
		
		UUID = playerFile.getName();
		
		final int dotPos = UUID.lastIndexOf('.');
		if (UUID.contains("-"))
		{
			UUID = UUID.substring(0, dotPos).replace("-", "");
		}
		else
		{
			name = UUID = UUID.substring(0, dotPos);
		}
		
		skinURL = null;
		
		InputStream in = null;
		NBTInputStream nbtIn = null;
		try
		{
			in = new FileInputStream(playerFile);
			nbtIn = new NBTInputStream(in);
			
			Tag tag = nbtIn.readTag();
			if (tag instanceof CompoundTag)
			{
				CompoundTag root = (CompoundTag)tag;
				parse(root);
			}
		}
		finally
		{
			if (nbtIn != null)
				nbtIn.close();
			
			if (in != null)
				in.close();
		}
	}
	
	public Player(String playerName, CompoundTag tag) throws Exception
	{
		this.name = playerName;
		
		parse(tag);
	}
	
	public Player(String name, String UUID, String skinURL)
	{
		this.name = name;
		this.UUID = UUID;
		this.skinURL = skinURL;
	}
	
	private void parse(CompoundTag root) throws Exception
	{
		dimension = Dimension.Terra;
		position = new Vector3d();
		inventory = new ArrayList<Item>();	
		
		health = NbtUtil.getShort(root, "Health", (short)0);
		air = NbtUtil.getShort(root, "Air", (short)0);
		food = NbtUtil.getInt(root, "foodLevel", 0);
		
		final int dimensionVal = NbtUtil.getInt(root, "Dimension", 0);
		if (dimensionVal == 0)
			dimension = Dimension.Terra;
		else if (dimensionVal == 1)
			dimension = Dimension.Ender;
		else if (dimensionVal == -1)
			dimension = Dimension.Nether;
		
		ListTag posList = NbtUtil.getChild(root, "Pos", ListTag.class);
		if (posList != null)
		{
			DoubleTag xTag = NbtUtil.getChild(posList, 0, DoubleTag.class);
			DoubleTag yTag = NbtUtil.getChild(posList, 1, DoubleTag.class);
			DoubleTag zTag = NbtUtil.getChild(posList, 2, DoubleTag.class);
			
			if (xTag != null && yTag != null && zTag != null)
			{
				position.set(xTag.getValue(), yTag.getValue(), zTag.getValue());
			}
		}
		
		IntTag spawnXTag = NbtUtil.getChild(root, "SpawnX", IntTag.class);
		IntTag spawnYTag = NbtUtil.getChild(root, "SpawnY", IntTag.class);
		IntTag spawnZTag = NbtUtil.getChild(root, "SpawnZ", IntTag.class);
		if (spawnXTag != null && spawnYTag != null && spawnZTag != null)
		{
			spawnPos = new Vector3l(spawnXTag.getValue(), spawnYTag.getValue(), spawnZTag.getValue());
		}
		
		xpLevel = NbtUtil.getInt(root, "XpLevel", 0);
		xpTotal = NbtUtil.getInt(root, "XpTotal", 0);
		
		// Parse inventory items (both inventory items and worn items)
		ListTag inventoryList = NbtUtil.getChild(root, "Inventory", ListTag.class);
		if (inventoryList != null)
		{
			for (Tag t : inventoryList.getValue())
			{
				if (t instanceof CompoundTag)
				{
					CompoundTag itemTag = (CompoundTag)t;
					
					ShortTag idTag = NbtUtil.getChild(itemTag, "id", ShortTag.class);
					ShortTag damageTag = NbtUtil.getChild(itemTag, "Damage", ShortTag.class);
					ByteTag countTag = NbtUtil.getChild(itemTag, "Count", ByteTag.class);
					ByteTag slotTag = NbtUtil.getChild(itemTag, "Slot", ByteTag.class);
					
					if (idTag != null && damageTag != null && countTag != null && slotTag != null)
					{
						inventory.add( new Item(idTag.getValue(), damageTag.getValue(), countTag.getValue(), slotTag.getValue()) );
					}
				}
			}
		}
	}
	
	public void requestPlayerInfo() throws Exception
	{
		if (this.getUUID().equals(this.getName()))
		{
			this.setSkinURL("http://www.minecraft.net/skin/"+this.getName()+".png");
		}
		else
		{
			String urlString = "https://sessionserver.mojang.com/session/minecraft/profile/"+this.getUUID();
			URL url = new URL(urlString);
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.addRequestProperty("Content-Type", "application/json");
			connection.setReadTimeout(15*1000);
			connection.connect();
			int responseCode = connection.getResponseCode();
			if (responseCode == 204)
				System.err.println("ERROR: Unrecognized UUID");
			else if (responseCode == 429)
				System.err.println("ERROR: Too many requests. You are only allowed to contact the Mojang session server once per minute per player.  Wait for a minute and try again.");

			BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
			StringBuilder builder = new StringBuilder();
			
			String line = null;
			while ((line = reader.readLine()) != null)
			{
				builder.append(line + "\n");
			}
			reader.close();
			
			JsonObject obj = new JsonParser().parse(builder.toString()).getAsJsonObject();
			this.setName(obj.get("name").getAsString());
			JsonObject textures = obj.get("properties").getAsJsonArray().get(0).getAsJsonObject();
			byte[] decoded = DatatypeConverter.parseBase64Binary(textures.get("value").getAsString());
			obj = new JsonParser().parse(new String(decoded, "UTF-8")).getAsJsonObject();
			boolean hasSkin = obj.get("textures").getAsJsonObject().has("SKIN");
			String textureUrl = null;
			if (hasSkin == true)
				textureUrl = obj.get("textures").getAsJsonObject().get("SKIN").getAsJsonObject().get("url").getAsString();
			this.setSkinURL(textureUrl);
		}
	}
	
	public String getName()
	{
		return name;
	}
	
	public void setName(String name)
	{
		this.name = name;
	}
	
	public String getSkinURL()
	{
		return skinURL;
	}
	
	public void setSkinURL(String skinURL)
	{
		this.skinURL = skinURL;
	}
	
	public String getUUID()
	{
		return UUID;
	}
	
	public Vector3d getPosition()
	{
		return new Vector3d(position);
	}
	
	public int getHealth()
	{
		return health;
	}
	
	public int getFood()
	{
		return food;
	}
	
	public int getAir()
	{
		return air;
	}
	
	public int getXpLevel()
	{
		return xpLevel;
	}
	
	public int getXpTotal()
	{
		return xpTotal;
	}
	
	public Dimension getDimension()
	{
		return dimension;
	}
	
	/** Caution - may be null if the player hasn't built a bed yet! */
	public Vector3l getSpawnPosition()
	{
		return spawnPos;
	}
}
