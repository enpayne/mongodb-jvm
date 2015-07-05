/**
 * Copyright 2010-2015 Three Crickets LLC.
 * <p>
 * The contents of this file are subject to the terms of the Apache License
 * version 2.0: http://www.opensource.org/licenses/apache2.0.php
 * <p>
 * Alternatively, you can obtain a royalty free commercial license with less
 * limitations, transferable or non-transferable, directly from Three Crickets
 * at http://threecrickets.com/
 */

package com.mongodb.jvm.json.java;

import java.util.Date;
import java.util.Map;

import com.threecrickets.jvm.json.JsonTransformer;

public class DateTransformer implements JsonTransformer
{
	//
	// JsonTransformer
	//

	public Object transform( Object object )
	{
		if( object instanceof Map )
		{
			@SuppressWarnings("unchecked")
			Object date = ( (Map<String, Object>) object ).get( "$date" );
			if( date instanceof Number )
				return new Date( ( (Number) date ).longValue() );
		}

		return null;
	}
}
