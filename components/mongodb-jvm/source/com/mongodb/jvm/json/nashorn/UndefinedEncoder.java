/**
 * Copyright 2010-2017 Three Crickets LLC.
 * <p>
 * The contents of this file are subject to the terms of the Apache License
 * version 2.0: http://www.opensource.org/licenses/apache2.0.php
 * <p>
 * Alternatively, you can obtain a royalty free commercial license with less
 * limitations, transferable or non-transferable, directly from Three Crickets
 * at http://threecrickets.com/
 */

package com.mongodb.jvm.json.nashorn;

import java.io.IOException;
import java.util.HashMap;

import com.threecrickets.jvm.json.JsonContext;
import com.threecrickets.jvm.json.JsonEncoder;
import com.threecrickets.jvm.json.generic.MapEncoder;

import jdk.nashorn.internal.runtime.Undefined;

/**
 * A JSON encoder for a Nashorn {@link Undefined}.
 * 
 * @author Tal Liron
 */
public class UndefinedEncoder implements JsonEncoder
{
	//
	// JsonEncoder
	//

	public boolean canEncode( Object object, JsonContext context )
	{
		return object instanceof Undefined;
	}

	public void encode( Object object, JsonContext context ) throws IOException
	{
		HashMap<String, Object> map = new HashMap<String, Object>();
		map.put( "$undefined", true );
		new MapEncoder().encode( map, context );
	}
}
