package org.codehaus.groovy.grails.plugins.codecs;

import java.io.IOException;

import org.codehaus.groovy.grails.support.encoding.CodecIdentifier;
import org.codehaus.groovy.grails.support.encoding.EncodedAppender;
import org.codehaus.groovy.grails.support.encoding.Encoder;
import org.codehaus.groovy.grails.support.encoding.EncodingState;
import org.codehaus.groovy.grails.support.encoding.StreamingEncoder;

public abstract class AbstractCharReplacementEncoder implements Encoder, StreamingEncoder {
    protected CodecIdentifier codecIdentifier;
    
    public AbstractCharReplacementEncoder(CodecIdentifier codecIdentifier) {
        this.codecIdentifier = codecIdentifier;
    }
    
    protected abstract String escapeCharacter(char ch, char previousChar);

    public Object encode(Object o) {
        if(o==null) return null;
        
        CharSequence str=null;
        if(o instanceof CharSequence) {
            str=(CharSequence)o;
        } else if (o instanceof Character) {
            String escaped=escapeCharacter((Character)o, (char)0);
            if(escaped != null) {
                return escaped;
            } else {
                return o;
            }
        } else {
            str=String.valueOf(o);
        }
        
        if(str.length()==0) {
            return str;
        }
        
        StringBuilder sb=null;
        int n = str.length(), i;
        int startPos=-1;
        char prevChar = (char)0;
        for (i = 0; i < n; i++) {
          char ch = str.charAt(i);
          if(startPos==-1) {
              startPos=i;
          }
          String escaped=escapeCharacter(ch, prevChar);
          if(escaped != null) {
              if(sb==null) {
                  sb=new StringBuilder(str.length() * 110 / 100);
              }
              if(i-startPos > 0) {
                  sb.append(str, startPos, i);
              }
              if(escaped.length() > 0) {
                  sb.append(escaped);
              }
              startPos=-1;
          }
          prevChar = ch;
        }
        if(sb != null) {
            if(startPos > -1 && i-startPos > 0) {
                sb.append(str, startPos, i);
            }
            return sb.toString();
        } else {
            return str;
        }
    }

    public void encodeToStream(CharSequence str, int off, int len, EncodedAppender appender, EncodingState encodingState) throws IOException {
        if(str==null || len <= 0) {
            return;
        }
        int n = Math.min(str.length(), off+len); 
        int i;
        int startPos=-1;
        char prevChar = (char)0;
        for (i = off; i < n; i++) {
          char ch = str.charAt(i);
          if(startPos==-1) {
              startPos=i;
          }
          String escaped=escapeCharacter(ch, prevChar);
          if(escaped != null) {
              if(i-startPos > 0) {
                  appender.append(this, encodingState, str, startPos, i-startPos);
              }
              if(escaped.length() > 0) {
                  appender.append(this, encodingState, escaped, 0, escaped.length());
              }
              startPos=-1;
          }
          prevChar = ch;
        }
        if(startPos > -1 && i-startPos > 0) {
            appender.append(this, encodingState, str, startPos, i-startPos);
        }
    }

    public void markEncoded(CharSequence string) {
        // no need to implement, wrapped automaticly
    }

    public boolean isSafe() {
        return true;
    }
    
    public CodecIdentifier getCodecIdentifier() {
        return codecIdentifier;
    }
}