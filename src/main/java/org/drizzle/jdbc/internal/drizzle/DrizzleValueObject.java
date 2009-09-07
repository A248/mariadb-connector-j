/*
 * Drizzle JDBC
 *
 * Copyright (C) 2009 Marcus Eriksson (krummas@gmail.com)
 * All rights reserved.
 *
 * Use and distribution licensed under the BSD license.
 */

package org.drizzle.jdbc.internal.drizzle;

import org.drizzle.jdbc.DrizzleBlob;
import org.drizzle.jdbc.internal.common.AbstractValueObject;

import java.text.ParseException;

/**
 * Contains the raw value returned from the server
 * <p/>
 * Is immutable
 * <p/>
 * User: marcuse
 * Date: Feb 16, 2009
 * Time: 9:18:26 PM
 */
public class DrizzleValueObject extends AbstractValueObject {

    public DrizzleValueObject(final byte[] rawBytes, final DrizzleType dataType) {
        super(rawBytes,dataType);
    }

    public Object getObject() throws ParseException  {
        if (this.getBytes() == null) {
            return null;
        }
        switch (((DrizzleType)dataType).getType()) {
            case TINY:
                return getShort();
            case LONG:
                return getLong();
            case DOUBLE:
                return getDouble();
            case TIMESTAMP:
                return getTimestamp();
            case LONGLONG:
                return getLong();
            case DATETIME:
                return getTimestamp();
            case DATE:
                return getDate();
            case VARCHAR:
                return getString();
            case NEWDECIMAL:
                return getBigDecimal();
            case ENUM:
                return getString();
            case BLOB:
                return new DrizzleBlob(getBytes());
            default:
                return null;
        }
    }
}