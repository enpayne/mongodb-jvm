/**
 * Copyright 2010-2013 Three Crickets LLC.
 * <p>
 * The contents of this file are subject to the terms of the Apache License
 * version 2.0: http://www.opensource.org/licenses/apache2.0.php
 * <p>
 * Alternatively, you can obtain a royalty free commercial license with less
 * limitations, transferable or non-transferable, directly from Three Crickets
 * at http://threecrickets.com/
 */

package com.mongodb.jvm.nashorn;

import java.util.Date;
import java.util.HashMap;
import java.util.regex.Pattern;

import jdk.nashorn.internal.objects.NativeDate;
import jdk.nashorn.internal.objects.NativeNumber;
import jdk.nashorn.internal.objects.NativeRegExp;
import jdk.nashorn.internal.runtime.NumberToString;
import jdk.nashorn.internal.runtime.ScriptFunction;
import jdk.nashorn.internal.runtime.ScriptObject;
import jdk.nashorn.internal.runtime.Undefined;

import org.bson.types.BSONTimestamp;
import org.bson.types.Binary;
import org.bson.types.ObjectId;

import com.mongodb.DBRefBase;
import com.mongodb.jvm.BSON;
import com.mongodb.jvm.internal.Base64;
import com.threecrickets.jvm.json.nashorn.NashornJsonExtender;
import com.threecrickets.jvm.json.nashorn.util.NashornNativeUtil;
import com.threecrickets.jvm.json.util.JavaScriptUtil;
import com.threecrickets.jvm.json.util.Literal;

/**
 * Conversion between native Nashorn values and <a
 * href="http://docs.mongodb.org/manual/reference/mongodb-extended-json/"
 * >MongoDB's extended JSON notation</a>.
 * <p>
 * Notations converted to org.bson.types: {$oid:'objectid'},
 * {$binary:'base64',$type:'hex'}, {$ref:'collection',$id:'objectid'}.
 * <p>
 * Native conversions: {$undefined:true} for {@link Undefined},
 * {$date:timestamp} and {$timestamp:{t:seconds,i:inc}} for
 * org.mozilla.javascript.NativeDate, and {$regex:'pattern',$options:'options'}
 * for {@link NativeRegExp}. When the "rhino" argument is true in
 * {@link #from(ScriptObject, boolean)}, JVM values will be used instead:
 * {@link Date}, {@link BSONTimestamp}, {@link Pattern}. These values are also
 * recognized in {@link #to(Object, boolean, boolean)}.
 * <p>
 * We also supports two additional extended notations, not defined by MongoDB:
 * <p>
 * {$function:'source'} for {@link ScriptFunction}.
 * <p>
 * {$long:'integer'} for {@link Long} and {$integer:'integer'} for
 * {@link Integer}. This string-based encoding is necessary for preserving
 * precision, because JavaScript only supports double values for numbers. Note
 * that the implementation makes sure to create a {@link Long} only if indeed
 * precision would be lost without it.
 * <p>
 * Also converts JVM byte arrays to {@link Binary}.
 * 
 * @author Tal Liron
 */
public class MongoNashornJsonExtender implements NashornJsonExtender
{
	//
	// RhinoJsonExtender
	//

	public Object from( ScriptObject script, boolean rhino )
	{
		Object longValue = getProperty( script, "$long" );
		if( longValue != null )
		{
			// Convert extended JSON $long format to Long

			if( longValue instanceof Number )
				return NashornNativeUtil.wrap( ( (Number) longValue ).longValue() );
			else
			{
				try
				{
					return NashornNativeUtil.wrap( Long.parseLong( longValue.toString() ) );
				}
				catch( NumberFormatException x )
				{
					throw new RuntimeException( "Invalid $long: " + longValue );
				}
			}
		}

		Object integerValue = getProperty( script, "$integer" );
		if( integerValue != null )
		{
			// Convert extended JSON $integer format to Integer

			if( integerValue instanceof Number )
				return NashornNativeUtil.wrap( ( (Number) integerValue ).intValue() );
			else
			{
				try
				{
					return NashornNativeUtil.wrap( Integer.parseInt( integerValue.toString() ) );
				}
				catch( NumberFormatException x )
				{
					throw new RuntimeException( "Invalid $integer: " + integerValue );
				}
			}
		}

		Object undefined = getProperty( script, "$undefined" );
		if( undefined != null )
		{
			// Convert extended JSON $undefined format to JavaScript undefined

			return Undefined.getUndefined();
		}

		Object functionValue = getProperty( script, "$function" );
		if( functionValue != null )
		{
			// Convert extended JSON $function format to JavaScript function

			return NashornNativeUtil.toFunction( functionValue );
		}

		Object dateValue = getProperty( script, "$date" );
		if( dateValue != null )
		{
			// Convert extended JSON $date format to Rhino/JVM date

			long dateTimestamp;

			if( dateValue instanceof NativeNumber )
				dateTimestamp = ( (NativeNumber) dateValue ).longValue();
			else if( dateValue instanceof ScriptObject )
			{
				longValue = getProperty( (ScriptObject) dateValue, "$long" );
				if( longValue != null )
				{
					if( longValue instanceof Number )
						dateTimestamp = ( (Number) longValue ).longValue();
					else
					{
						try
						{
							dateTimestamp = Long.parseLong( longValue.toString() );
						}
						catch( NumberFormatException x )
						{
							throw new RuntimeException( "Invalid $long: " + longValue );
						}
					}
				}
				else
					throw new RuntimeException( "Invalid $date: " + dateValue );
			}
			else
			{
				try
				{
					dateTimestamp = Long.parseLong( dateValue.toString() );
				}
				catch( NumberFormatException x )
				{
					throw new RuntimeException( "Invalid $date: " + dateValue );
				}
			}

			Date date = new Date( dateTimestamp );

			if( rhino )
				return NashornNativeUtil.to( date );
			else
				return date;
		}

		Object timestampValue = getProperty( script, "$timestamp" );
		if( timestampValue != null )
		{
			// Convert extended JSON $timestamp format to MongoDB BSONTimestamp
			// or Rhino date

			int t, i;

			if( timestampValue instanceof ScriptObject )
			{
				Object tValue = getProperty( (ScriptObject) timestampValue, "t" );
				Object iValue = getProperty( (ScriptObject) timestampValue, "i" );

				if( ( tValue instanceof Number ) && ( iValue instanceof Number ) )
				{
					t = ( (Number) tValue ).intValue();
					i = ( (Number) iValue ).intValue();
				}
				else
					throw new RuntimeException( "Invalid $timestamp: " + timestampValue );
			}
			else
				throw new RuntimeException( "Invalid $timestamp: " + timestampValue );

			BSONTimestamp timestamp = new BSONTimestamp( t, i );

			if( rhino )
				return NashornNativeUtil.to( new Date( timestamp.getTime() * 1000L ) );
			else
				return timestamp;
		}

		if( rhino )
		{
			Object regex = getProperty( script, "$regex" );
			if( regex != null )
			{
				// Convert extended JSON $regex format to Rhino RegExp

				String source = regex.toString();
				Object options = getProperty( script, "$options" );
				String optionsString = "";
				if( options != null )
					optionsString = options.toString();

				return NashornNativeUtil.toRegExp( source, optionsString );
			}
		}

		Object oid = getProperty( script, "$oid" );
		if( oid != null )
		{
			// Convert extended JSON $oid format to MongoDB ObjectId

			return new ObjectId( oid.toString() );
		}

		Object binary = getProperty( script, "$binary" );
		if( binary != null )
		{
			// Convert extended JSON $binary format to MongoDB Binary

			Object type = getProperty( script, "$type" );
			byte typeNumber = type != null ? Byte.valueOf( type.toString(), 16 ) : 0;
			byte[] data = Base64.decodeFast( binary.toString() );
			return new Binary( typeNumber, data );
		}

		Object ref = getProperty( script, "$ref" );
		if( ref != null )
		{
			// Convert extended JSON $ref format to MongoDB DBRef

			Object id = getProperty( script, "$id" );
			if( id != null )
			{
				String idString = null;
				if( id instanceof ScriptObject )
				{
					Object idOid = getProperty( (ScriptObject) id, "$oid" );
					if( idOid != null )
						idString = idOid.toString();
				}
				if( idString == null )
					idString = id.toString();

				return new DBRefBase( null, ref.toString(), idString );
			}
		}

		return null;
	}

	/**
	 * Converts BSON, byte arrays, java.util.Date, java.util.regex.Pattern,
	 * java.lang.Long, and JavaScript Date, RegExp and Function objects to
	 * MongoDB's extended JSON.
	 * <p>
	 * Note that java.lang.Long will be converted only if necessary in order to
	 * preserve its value when converted to a JavaScript Number object.
	 * <p>
	 * The output can be either a native Rhino object or a java.util.HashMap.
	 * <p>
	 * A special "JavaScript" mode allows dumping JavaScript literals (for Date,
	 * RegExp and functions), though note this will break JSON compatibility!
	 * 
	 * @param object
	 * @param nashorn
	 *        True to create Rhino native objects, otherwise a java.util.HashMap
	 *        will be used
	 * @param javaScript
	 *        True to allow JavaScript literals (these will break JSON
	 *        compatibility!)
	 * @return A JavaScript object, a java.util.HashMap or null if not converted
	 */
	public Object to( Object object, boolean nashorn, boolean javaScript )
	{
		if( object instanceof Long )
		{
			// Convert Long to extended JSON $long format

			Long longValue = (Long) object;
			String longString = longValue.toString();

			// If the numerical value can be converted to a string via
			// JavaScript without loss of information, then there's no need to
			// convert to extended JSON

			String convertedString = NumberToString.stringFor( longValue );
			if( longValue.equals( Long.valueOf( convertedString ) ) )
				return null;

			if( nashorn )
			{
				ScriptObject nativeObject = NashornNativeUtil.newObject();
				nativeObject.put( "$long", longString, false );
				return nativeObject;
			}
			else
			{
				HashMap<String, String> map = new HashMap<String, String>( 1 );
				map.put( "$long", longString );
				return map;
			}
		}
		else if( object instanceof Date )
		{
			// Convert Date to extended JSON $date format

			long time = ( (Date) object ).getTime();
			if( javaScript )
			{
				return new Literal( "new Date(" + NumberToString.stringFor( time ) + ")" );
			}
			else if( nashorn )
			{
				ScriptObject nativeObject = NashornNativeUtil.newObject();
				nativeObject.put( "$date", NashornNativeUtil.wrap( time ), false );
				return nativeObject;
			}
			else
			{
				HashMap<String, Long> map = new HashMap<String, Long>( 1 );
				map.put( "$date", time );
				return map;
			}
		}
		else if( object instanceof NativeRegExp )
		{
			// Convert NativeRegExp to extended JSON $regex format

			String[] regExp = NashornNativeUtil.from( (NativeRegExp) object );

			if( javaScript )
			{
				if( ( regExp[1] != null ) && ( regExp[1].length() > 0 ) )
					return new Literal( "new RegExp(\"" + JavaScriptUtil.escape( regExp[0] ) + "\", \"" + JavaScriptUtil.escape( regExp[1] ) + "\")" );
				else
					return new Literal( "new RegExp(\"" + JavaScriptUtil.escape( regExp[0] ) + "\")" );
			}
			else if( nashorn )
			{
				ScriptObject nativeObject = NashornNativeUtil.newObject();
				nativeObject.put( "$regex", regExp[0], false );
				nativeObject.put( "$options", regExp[1], false );
				return nativeObject;
			}
			else
			{
				HashMap<String, String> map = new HashMap<String, String>( 2 );
				map.put( "$regex", regExp[0] );
				map.put( "$options", regExp[1] );
				return map;
			}
		}
		else if( object instanceof ScriptFunction )
		{
			// Convert Function to extended JSON $function format

			String source = ( (ScriptFunction) object ).toSource().trim();

			if( javaScript )
			{
				return new Literal( source );
			}
			else if( nashorn )
			{
				ScriptObject nativeObject = NashornNativeUtil.newObject();
				nativeObject.put( "$function", source, false );
				return nativeObject;
			}
			else
			{
				HashMap<String, String> map = new HashMap<String, String>( 1 );
				map.put( "$function", source );
				return map;
			}
		}
		else if( object instanceof NativeDate )
		{
			// Convert NativeDate to extended JSON $date format

			Object time = NativeDate.getTime( object );
			if( time instanceof Number )
			{
				long timestamp = ( (Number) time ).longValue();
				if( nashorn )
				{
					ScriptObject nativeObject = NashornNativeUtil.newObject();
					nativeObject.put( "$date", timestamp, false );
					return nativeObject;
				}
				else
				{
					HashMap<String, Long> map = new HashMap<String, Long>( 1 );
					map.put( "$date", timestamp );
					return map;
				}
			}
		}
		else if( object instanceof Pattern )
		{
			// Convert Pattern to extended JSON $regex format

			// (Note: Pattern does not support JavaScript's 'g' option;
			// also, there may be incompatibilities between Pattern's and
			// JavaScript's regular expression implementations)

			Pattern pattern = (Pattern) object;
			String regex = pattern.toString();
			int flags = pattern.flags();
			String options = "";
			if( ( flags & Pattern.CASE_INSENSITIVE ) != 0 )
				options += 'i';
			if( ( flags & Pattern.MULTILINE ) != 0 )
				options += 'm';

			if( javaScript )
			{
				if( options.length() > 0 )
					return new Literal( "new RegExp(\"" + JavaScriptUtil.escape( regex ) + "\", \"" + JavaScriptUtil.escape( options ) + "\")" );
				else
					return new Literal( "new RegExp(\"" + JavaScriptUtil.escape( regex ) + "\")" );
			}
			else if( nashorn )
			{
				ScriptObject nativeObject = NashornNativeUtil.newObject();
				nativeObject.put( "$regex", regex, false );
				nativeObject.put( "$options", options, false );
				return nativeObject;
			}
			else
			{
				HashMap<String, String> map = new HashMap<String, String>( 2 );
				map.put( "$regex", regex );
				map.put( "$options", options );
				return map;
			}
		}
		else if( object instanceof ObjectId )
		{
			// Convert MongoDB ObjectId to extended JSON $oid format

			String oid = ( (ObjectId) object ).toStringMongod();
			if( nashorn )
			{
				ScriptObject nativeObject = NashornNativeUtil.newObject();
				nativeObject.put( "$oid", oid, false );
				return nativeObject;
			}
			else
			{
				HashMap<String, String> map = new HashMap<String, String>( 1 );
				map.put( "$oid", ( (ObjectId) object ).toStringMongod() );
				return map;
			}
		}
		else if( object instanceof Binary )
		{
			// Convert MongoDB Binary to extended JSON $binary format

			Binary binary = (Binary) object;
			String data = Base64.encodeToString( binary.getData(), false );
			String type = Integer.toHexString( binary.getType() );
			if( nashorn )
			{
				ScriptObject nativeObject = NashornNativeUtil.newObject();
				nativeObject.put( "$binary", data, false );
				nativeObject.put( "$type", type, false );
				return nativeObject;
			}
			else
			{
				HashMap<String, String> map = new HashMap<String, String>( 2 );
				map.put( "$binary", data );
				map.put( "$type", type );
				return map;
			}
		}
		else if( object instanceof byte[] )
		{
			// Convert byte array to extended JSON $binary format

			byte[] bytes = (byte[]) object;
			String data = Base64.encodeToString( bytes, false );
			String type = Integer.toHexString( 0 );
			if( nashorn )
			{
				ScriptObject nativeObject = NashornNativeUtil.newObject();
				nativeObject.put( "$binary", data, false );
				nativeObject.put( "$type", type, false );
				return nativeObject;
			}
			else
			{
				HashMap<String, String> map = new HashMap<String, String>( 2 );
				map.put( "$binary", data );
				map.put( "$type", type );
				return map;
			}
		}
		else if( object instanceof DBRefBase )
		{
			// Convert MongoDB ref to extended JSON $ref format

			DBRefBase ref = (DBRefBase) object;
			String collection = ref.getRef();
			Object id = BSON.from( ref.getId(), true );
			String idString;
			if( id instanceof ObjectId )
				idString = ( (ObjectId) id ).toStringMongod();
			else
				// Seems like this will break for aggregate _ids, but this is
				// what the MongoDB documentation says!
				idString = id.toString();

			if( nashorn )
			{
				ScriptObject nativeObject = NashornNativeUtil.newObject();
				nativeObject.put( "$ref", collection, false );
				nativeObject.put( "$id", idString, false );
				return nativeObject;
			}
			else
			{
				HashMap<String, String> map = new HashMap<String, String>( 2 );
				map.put( "$ref", collection );
				map.put( "$id", idString );
				return map;
			}
		}
		else if( object instanceof BSONTimestamp )
		{
			// Convert MongoDB BSONTimestamp to extended JSON $timestamp format

			BSONTimestamp timestamp = (BSONTimestamp) object;
			int t = timestamp.getTime();
			int i = timestamp.getInc();

			if( nashorn )
			{
				ScriptObject nativeObject = NashornNativeUtil.newObject();
				nativeObject.put( "t", t, false );
				nativeObject.put( "i", i, false );
				return nativeObject;
			}
			else
			{
				HashMap<String, Integer> map = new HashMap<String, Integer>( 2 );
				map.put( "t", t );
				map.put( "i", i );
				return map;
			}
		}

		return null;
	}

	// //////////////////////////////////////////////////////////////////////////
	// Private

	private static Object getProperty( ScriptObject script, String key )
	{
		Object value = script.get( key );
		if( value instanceof Undefined )
			return null;
		return value;
	}
}
