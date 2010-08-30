/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transformer.simple;

import org.mule.RequestContext;
import org.mule.api.transformer.DiscoverableTransformer;
import org.mule.api.transformer.TransformerException;
import org.mule.api.transport.OutputHandler;
import org.mule.config.i18n.CoreMessages;
import org.mule.transformer.AbstractTransformer;
import org.mule.transformer.types.DataTypeFactory;
import org.mule.util.IOUtils;
import org.mule.util.StringMessageUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

/**
 * <code>ObjectToString</code> transformer is useful for debugging. It will return
 * human-readable output for various kinds of objects. Right now, it is just coded to
 * handle Map and Collection objects. Others will be added.
 */
public class ObjectToString extends AbstractTransformer implements DiscoverableTransformer
{
    protected static final int DEFAULT_BUFFER_SIZE = 80;

    /** Give core transformers a slighty higher priority */
    private int priorityWeighting = DiscoverableTransformer.DEFAULT_PRIORITY_WEIGHTING + 1;

    public ObjectToString()
    {
        registerSourceType(DataTypeFactory.OBJECT);
        registerSourceType(DataTypeFactory.BYTE_ARRAY);
        registerSourceType(DataTypeFactory.INPUT_STREAM);
        registerSourceType(DataTypeFactory.create(OutputHandler.class));
        //deliberately set the mime for this transformer to text plain so that other transformers
        //that serialize string types such as XML or JSON will not match this
        setReturnDataType(DataTypeFactory.TEXT_STRING);
    }

    @Override
    public Object doTransform(Object src, String encoding) throws TransformerException
    {
        String output = "";

        if (src instanceof InputStream)
        {
            InputStream is = (InputStream) src;
            try
            {
                ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                IOUtils.copy(is, byteOut);
                output = new String(byteOut.toByteArray(), encoding);
            }
            catch (IOException e)
            {
                throw new TransformerException(CoreMessages.errorReadingStream(), e);
            }
            finally
            {
                try
                {
                    is.close();
                }
                catch (IOException e)
                {
                    logger.warn("Could not close stream", e);
                }
            }
        }
        else if (src instanceof OutputHandler)
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            
            try
            {
                ((OutputHandler) src).write(RequestContext.getEvent(), bytes);
                
                output = new String(bytes.toByteArray(), encoding);
            }
            catch (IOException e)
            {
                throw new TransformerException(this, e);
            }
            
            
        }
        else if (src instanceof byte[])
        {
            try
            {
                output = new String((byte[]) src, encoding);
            }
            catch (UnsupportedEncodingException e)
            {
                throw new TransformerException(this, e);
            }
        }
        else
        {
            output = StringMessageUtils.toString(src);
        }

        return output;
    }

    public int getPriorityWeighting()
    {
        return priorityWeighting;
    }

    public void setPriorityWeighting(int priorityWeighting)
    {
        this.priorityWeighting = priorityWeighting;
    }
}
