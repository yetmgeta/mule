/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport.http.transformers;

import org.mule.api.MuleMessage;
import org.mule.api.config.MuleProperties;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.transformer.TransformerException;
import org.mule.config.MuleManifest;
import org.mule.transformer.AbstractMessageTransformer;
import org.mule.transformer.types.DataTypeFactory;
import org.mule.transport.NullPayload;
import org.mule.transport.http.HttpConnector;
import org.mule.transport.http.HttpConstants;
import org.mule.transport.http.HttpResponse;
import org.mule.transport.http.i18n.HttpMessages;
import org.mule.util.StringUtils;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpVersion;

/**
 * Converts a {@link MuleMessage} into an Http response.
 */
public class MuleMessageToHttpResponse extends AbstractMessageTransformer
{
    public static final String CUSTOM_HEADER_PREFIX = "";

    private String server;

    public MuleMessageToHttpResponse()
    {
        registerSourceType(DataTypeFactory.OBJECT);
        setReturnDataType(DataTypeFactory.create(HttpResponse.class));
    }

    @Override
    public void initialise() throws InitialisationException
    {
        // When running with the source code, Meta information is not set
        // so product name and version are not available, hence we hard code
        if (MuleManifest.getProductName() == null)
        {
            server = "Mule/SNAPSHOT";
        }
        else
        {
            server = MuleManifest.getProductName() + "/" + MuleManifest.getProductVersion();
        }
    }

    @Override
    public Object transformMessage(MuleMessage msg, String outputEncoding) throws TransformerException
    {
        Object src = msg.getPayload();

        // Note this transformer excepts Null as we must always return a result
        // from the Http
        // connector if a response transformer is present
        if (src instanceof NullPayload)
        {
            src = StringUtils.EMPTY;
        }

        try
        {
            HttpResponse response;
            if (src instanceof HttpResponse)
            {
                response = (HttpResponse) src;
            }
            else
            {
                response = createResponse(src, outputEncoding, msg);
            }

            // Ensure there's a content type header
            if (!response.containsHeader(HttpConstants.HEADER_CONTENT_TYPE))
            {
                response.addHeader(new Header(HttpConstants.HEADER_CONTENT_TYPE,
                        HttpConstants.DEFAULT_CONTENT_TYPE));
            }

            // Ensure there's a content length or transfer encoding header
            if (!response.containsHeader(HttpConstants.HEADER_CONTENT_LENGTH)
                    && !response.containsHeader(HttpConstants.HEADER_TRANSFER_ENCODING))
            {
                if (response.hasBody())
                {
                    long len = response.getContentLength();
                    if (len < 0)
                    {
                        if (response.getHttpVersion().lessEquals(HttpVersion.HTTP_1_0))
                        {
                            // Ensure that we convert the payload to an in memory representation
                            // so we don't end up with a chunked response
                            len = msg.getPayloadAsBytes().length;

                            response.setBody(msg);

                            Header header = new Header(HttpConstants.HEADER_CONTENT_LENGTH, Long.toString(len));
                            response.setHeader(header);
                        }
                        else
                        {
                            Header header = new Header(HttpConstants.HEADER_TRANSFER_ENCODING, "chunked");
                            response.addHeader(header);
                        }
                    }
                    else
                    {
                        Header header = new Header(HttpConstants.HEADER_CONTENT_LENGTH, Long.toString(len));
                        response.setHeader(header);
                    }
                }
                else
                {
                    Header header = new Header(HttpConstants.HEADER_CONTENT_LENGTH, "0");
                    response.addHeader(header);
                }
            }

            // See if the the client explicitly handles connection persistence
            String connHeader = msg.getOutboundProperty(HttpConstants.HEADER_CONNECTION);
            if (connHeader != null)
            {
                if (connHeader.equalsIgnoreCase("keep-alive"))
                {
                    response.setKeepAlive(true);
                }
                if (connHeader.equalsIgnoreCase("close"))
                {
                    response.setKeepAlive(false);
                }
            }

            final String method = msg.getOutboundProperty(HttpConnector.HTTP_METHOD_PROPERTY);
            if ("HEAD".equalsIgnoreCase(method))
            {
                // this is a head request, we don't want to send the actual content
                response.setBody((MuleMessage) null);
            }
            return response;
        }
        catch (Exception e)
        {
            throw new TransformerException(this, e);
        }

    }

    protected HttpResponse createResponse(Object src, String encoding, MuleMessage msg)
            throws IOException, TransformerException
    {
        HttpResponse response = new HttpResponse();

        Object tmp = msg.getOutboundProperty(HttpConnector.HTTP_STATUS_PROPERTY);
        int status = HttpConstants.SC_OK;

        if (tmp != null)
        {
            status = Integer.valueOf(tmp.toString());
        }
        else if (msg.getExceptionPayload() != null)
        {
            status = HttpConstants.SC_INTERNAL_SERVER_ERROR;
        }

        String version = msg.getInboundProperty(HttpConnector.HTTP_VERSION_PROPERTY);
        if (version == null)
        {
            version = HttpConstants.HTTP11;
        }
        String date = new SimpleDateFormat(HttpConstants.DATE_FORMAT, Locale.US).format(new Date());

        String contentType = msg.getInboundProperty(HttpConstants.HEADER_CONTENT_TYPE);
        if (contentType == null)
        {
            contentType = msg.getInvocationProperty(HttpConstants.HEADER_CONTENT_TYPE);
        }

        // MULE-4047 Don't explicitly set the content-type to a default value here,
        // otherwise any settings on the servlet/transport will be happily ignored.
        //if (contentType == null)
        //{
        //    contentType = HttpConstants.DEFAULT_CONTENT_TYPE;
        //
        //    if (encoding != null)
        //    {
        //        contentType += "; charset=" + encoding;
        //    }
        //    logger.warn("Content-Type was not set, defaulting to: " + contentType);
        //}

        response.setStatusLine(HttpVersion.parse(version), status);
        if (contentType != null)
        {
            response.setHeader(new Header(HttpConstants.HEADER_CONTENT_TYPE, contentType));
        }
        response.setHeader(new Header(HttpConstants.HEADER_DATE, date));
        response.setHeader(new Header(HttpConstants.HEADER_SERVER, server));
        if (msg.getOutboundProperty(HttpConstants.HEADER_EXPIRES) == null)
        {
            response.setHeader(new Header(HttpConstants.HEADER_EXPIRES, date));
        }

        String etag = msg.getOutboundProperty(HttpConstants.HEADER_ETAG);
        if (etag != null)
        {
            response.setHeader(new Header(HttpConstants.HEADER_ETAG, etag));
        }
        response.setFallbackCharset(encoding);

        @SuppressWarnings("unchecked")
        Collection<String> headerNames = HttpConstants.RESPONSE_HEADER_NAMES.values();

        for (String headerName : headerNames)
        {
            String value = msg.getInvocationProperty(headerName);
            if (value == null)
            {
                value = msg.getOutboundProperty(headerName);
            }
            if (value != null)
            {
                response.setHeader(new Header(headerName, value));
            }
        }

        Map customHeaders = msg.getOutboundProperty(HttpConnector.HTTP_CUSTOM_HEADERS_MAP_PROPERTY);
        if (customHeaders != null)
        {
            throw new TransformerException(HttpMessages.customHeaderMapDeprecated(), this);
        }

        //attach the outbound properties to the message
        for (String headerName : msg.getOutboundPropertyNames())
        {
            Object v = msg.getOutboundProperty(headerName);
            if (v != null)
            {
                response.setHeader(new Header(headerName, v.toString()));
            }
        }

        // Mule properties
        String user = msg.getOutboundProperty(MuleProperties.MULE_USER_PROPERTY);
        if (user != null)
        {
            response.setHeader(new Header(CUSTOM_HEADER_PREFIX + MuleProperties.MULE_USER_PROPERTY, user));
        }
        if (msg.getCorrelationId() != null)
        {
            response.setHeader(new Header(CUSTOM_HEADER_PREFIX + MuleProperties.MULE_CORRELATION_ID_PROPERTY,
                    msg.getCorrelationId()));
            response.setHeader(new Header(CUSTOM_HEADER_PREFIX
                    + MuleProperties.MULE_CORRELATION_GROUP_SIZE_PROPERTY,
                    String.valueOf(msg.getCorrelationGroupSize())));
            response.setHeader(new Header(CUSTOM_HEADER_PREFIX
                    + MuleProperties.MULE_CORRELATION_SEQUENCE_PROPERTY,
                    String.valueOf(msg.getCorrelationSequence())));
        }
        if (msg.getReplyTo() != null)
        {
            response.setHeader(new Header(CUSTOM_HEADER_PREFIX + MuleProperties.MULE_REPLY_TO_PROPERTY,
                    msg.getReplyTo().toString()));
        }

        try
        {
            response.setBody(msg);
        }
        catch (Exception e)
        {
            throw new TransformerException(this, e);
        }

        return response;
    }

    @Override
    public boolean isAcceptNull()
    {
        return true;
    }
}
