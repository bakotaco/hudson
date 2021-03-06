/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Michael B. Donohue, Seiji Sogabe
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import hudson.model.Queue.*;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * Cause object base class.  This class hierarchy is used to keep track of why 
 * a given build was started. This object encapsulates the UI rendering of the cause,
 * as well as providing more useful information in respective subypes.
 *
 * The Cause object is connected to a build via the {@link CauseAction} object.
 *
 * <h2>Views</h2>
 * <dl>
 * <dt>description.jelly
 * <dd>Renders the cause to HTML. By default, it puts the short description.
 * </dl>
 *
 * @author Michael Donohue
 * @see Run#getCauses()
 * @see Queue.Item#getCauses()
 */
@ExportedBean
public abstract class Cause {
    /**
     * One-line human-readable text of the cause.
     *
     * <p>
     * By default, this method is used to render HTML as well.
     */
    @Exported(visibility=3)
    abstract public String getShortDescription();

    /**
     * Fall back implementation when no other type is available.
     * @deprecated since 2009-02-08
     */
    public static class LegacyCodeCause extends Cause {
        private StackTraceElement [] stackTrace;
        public LegacyCodeCause() {
            stackTrace = new Exception().getStackTrace();
        }
        
        @Override
        public String getShortDescription() {
            return Messages.Cause_LegacyCodeCause_ShortDescription();
        }
    }
    
    /**
     * A build is triggered by the completion of another build (AKA upstream build.)
     */
    public static class UpstreamCause extends Cause {
        private String upstreamProject, upstreamUrl;
        private int upstreamBuild;
        /**
         * @deprecated since 2009-02-28
         */
        @Deprecated
        private transient Cause upstreamCause;
        private List<Cause> upstreamCauses;

        /**
         * @deprecated since 2009-02-28
         */
        // for backward bytecode compatibility
        public UpstreamCause(AbstractBuild<?,?> up) {
            this((Run<?,?>)up);
        }
        
        public UpstreamCause(Run<?, ?> up) {
            upstreamBuild = up.getNumber();
            upstreamProject = up.getParent().getName();
            upstreamUrl = up.getParent().getUrl();
            upstreamCauses = new ArrayList<Cause>(up.getCauses());
        }

        @Exported(visibility=3)
        public String getUpstreamProject() {
            return upstreamProject;
        }

        @Exported(visibility=3)
        public int getUpstreamBuild() {
            return upstreamBuild;
        }

        @Exported(visibility=3)
        public String getUpstreamUrl() {
            return upstreamUrl;
        }
        
        @Override
        public String getShortDescription() {
            return Messages.Cause_UpstreamCause_ShortDescription(upstreamProject, Integer.toString(upstreamBuild));
        }
        
        private Object readResolve() {
            if(upstreamCause != null) {
                if(upstreamCauses == null) upstreamCauses = new ArrayList<Cause>();
                upstreamCauses.add(upstreamCause);
                upstreamCause=null;
            }
            return this;
        }
    }

    /**
     * A build is started by an user action.
     */
    public static class UserCause extends Cause {
        private String authenticationName;
        public UserCause() {
            this.authenticationName = Hudson.getAuthentication().getName();
        }

        @Exported(visibility=3)
        public String getUserName() {
            return authenticationName;
        }

        @Override
        public String getShortDescription() {
            return Messages.Cause_UserCause_ShortDescription(authenticationName);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof UserCause && Arrays.equals(new Object[] {authenticationName},
                    new Object[] {((UserCause)o).authenticationName});
        }

        @Override
        public int hashCode() {
            return 295 + (this.authenticationName != null ? this.authenticationName.hashCode() : 0);
        }
    }

    public static class RemoteCause extends Cause {
        private String addr;
        private String note;

        public RemoteCause(String host, String note) {
            this.addr = host;
            this.note = note;
        }

        @Override
        public String getShortDescription() {
            if(note != null) {
                return Messages.Cause_RemoteCause_ShortDescriptionWithNote(addr, note);
            } else {
                return Messages.Cause_RemoteCause_ShortDescription(addr);
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RemoteCause && Arrays.equals(new Object[] {addr, note},
                    new Object[] {((RemoteCause)o).addr, ((RemoteCause)o).note});
        }

        @Override
        public int hashCode() {
            int hash = 5;
            hash = 83 * hash + (this.addr != null ? this.addr.hashCode() : 0);
            hash = 83 * hash + (this.note != null ? this.note.hashCode() : 0);
            return hash;
        }
    }
}
