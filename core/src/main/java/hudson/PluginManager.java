/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Stephen Connolly, Tom Huybrechts
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
package hudson;

import static hudson.init.InitMilestone.PLUGINS_PREPARED;
import static hudson.init.InitMilestone.PLUGINS_STARTED;
import static hudson.init.InitMilestone.PLUGINS_LISTED;

import hudson.PluginWrapper.Dependency;
import hudson.init.InitStrategy;
import hudson.model.AbstractModelObject;
import hudson.model.Failure;
import hudson.model.Hudson;
import hudson.model.UpdateCenter;
import hudson.model.UpdateSite;
import hudson.util.CyclicGraphDetector;
import hudson.util.CyclicGraphDetector.CycleDetectedException;
import hudson.util.Service;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.LogFactory;
import org.jvnet.hudson.reactor.Executable;
import org.jvnet.hudson.reactor.Reactor;
import org.jvnet.hudson.reactor.TaskBuilder;
import org.jvnet.hudson.reactor.TaskGraphBuilder;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.WebApp;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages {@link PluginWrapper}s.
 *
 * @author Kohsuke Kawaguchi
 */
public final class PluginManager extends AbstractModelObject {
    /**
     * All discovered plugins.
     */
    private final List<PluginWrapper> plugins = new ArrayList<PluginWrapper>();

    /**
     * All active plugins.
     */
    private final List<PluginWrapper> activePlugins = new ArrayList<PluginWrapper>();

    private final List<FailedPlugin> failedPlugins = new ArrayList<FailedPlugin>();

    /**
     * Plug-in root directory.
     */
    public final File rootDir;

    public final ServletContext context;

    /**
     * {@link ClassLoader} that can load all the publicly visible classes from plugins
     * (and including the classloader that loads Hudson itself.)
     *
     */
    // implementation is minimal --- just enough to run XStream
    // and load plugin-contributed classes.
    public final ClassLoader uberClassLoader = new UberClassLoader();

    /**
     * Once plugin is uploaded, this flag becomes true.
     * This is used to report a message that Hudson needs to be restarted
     * for new plugins to take effect.
     */
    public volatile boolean pluginUploaded = false;
    
    /**
     * Strategy for creating and initializing plugins
     */
    private final PluginStrategy strategy;

    public PluginManager(ServletContext context) {
        this.context = context;
        // JSON binding needs to be able to see all the classes from all the plugins
        WebApp.get(context).setClassLoader(uberClassLoader);

        rootDir = new File(Hudson.getInstance().getRootDir(),"plugins");
        if(!rootDir.exists())
            rootDir.mkdirs();
        
        strategy = createPluginStrategy();
    }

    /**
     * Called immediately after the construction.
     * This is a separate method so that code executed from here will see a valid value in
     * {@link Hudson#pluginManager}. 
     */
    public TaskBuilder initTasks(final InitStrategy initStrategy) {
        return new TaskGraphBuilder() {
            List<File> archives;
            Collection<String> bundledPlugins;

            {
                Handle loadBundledPlugins = add("Loading bundled plugins", new Executable() {
                    public void run(Reactor session) throws Exception {
                        bundledPlugins = loadBundledPlugins();
                    }
                });

                Handle listUpPlugins = requires(loadBundledPlugins).add("Listing up plugins", new Executable() {
                    public void run(Reactor session) throws Exception {
                        archives = initStrategy.listPluginArchives(PluginManager.this);
                    }
                });

                requires(listUpPlugins).attains(PLUGINS_LISTED).add("Preparing plugins",new Executable() {
                    public void run(Reactor session) throws Exception {
                        TaskGraphBuilder g = new TaskGraphBuilder();

                        final Map<String,File> inspectedShortNames = new HashMap<String,File>();

                        for( final File arc : archives ) {
                            g.followedBy().notFatal().attains(PLUGINS_LISTED).add("Inspecting plugin " + arc, new Executable() {
                                public void run(Reactor session) throws Exception {
                                    try {
                                        PluginWrapper p = strategy.createPluginWrapper(arc);
                                        if (isDuplicate(p)) return;

                                        p.isBundled = bundledPlugins.contains(arc.getName());
                                        plugins.add(p);
                                        if(p.isActive())
                                            activePlugins.add(p);
                                    } catch (IOException e) {
                                        failedPlugins.add(new FailedPlugin(arc.getName(),e));
                                        throw e;
                                    }
                                }

                                /**
                                 * Inspects duplication. this happens when you run hpi:run on a bundled plugin,
                                 * as well as putting numbered hpi files, like "cobertura-1.0.hpi" and "cobertura-1.1.hpi"
                                 */
                                private boolean isDuplicate(PluginWrapper p) {
                                    String shortName = p.getShortName();
                                    if (inspectedShortNames.containsKey(shortName)) {
                                        LOGGER.info("Ignoring "+arc+" because "+inspectedShortNames.get(shortName)+" is already loaded");
                                        return true;
                                    }

                                    inspectedShortNames.put(shortName,arc);
                                    return false;
                                }
                            });
                        }

                        g.requires(PLUGINS_LISTED).add("Checking cyclic dependencies",new Executable() {
                            /**
                             * Makes sure there's no cycle in dependencies.
                             */
                            public void run(Reactor reactor) throws Exception {
                                try {
                                    new CyclicGraphDetector<PluginWrapper>() {
                                        @Override
                                        protected List<PluginWrapper> getEdges(PluginWrapper p) {
                                            List<PluginWrapper> next = new ArrayList<PluginWrapper>();
                                            addTo(p.getDependencies(),next);
                                            addTo(p.getOptionalDependencies(),next);
                                            return next;
                                        }

                                        private void addTo(List<Dependency> dependencies, List<PluginWrapper> r) {
                                            for (Dependency d : dependencies) {
                                                PluginWrapper p = getPlugin(d.shortName);
                                                if (p!=null)
                                                    r.add(p);
                                            }
                                        }
                                    }.run(getPlugins());
                                } catch (CycleDetectedException e) {
                                    stop(); // disable all plugins since classloading from them can lead to StackOverflow
                                    throw e;    // let Hudson fail
                                }
                            }
                        });

                        g.requires(PLUGINS_LISTED).attains(PLUGINS_PREPARED).add("Loading plugins",new Executable() {
                            /**
                             * Once the plugins are listed, schedule their initialization.
                             */
                            public void run(Reactor session) throws Exception {
                                TaskGraphBuilder g = new TaskGraphBuilder();

                                // schedule execution of loading plugins
                                for (final PluginWrapper p : activePlugins.toArray(new PluginWrapper[activePlugins.size()])) {
                                    g.followedBy().notFatal().attains(PLUGINS_PREPARED).add("Loading plugin " + p.getShortName(), new Executable() {
                                        public void run(Reactor session) throws Exception {
                                            try {
                                                strategy.load(p);
                                            } catch (IOException e) {
                                                failedPlugins.add(new FailedPlugin(p.getShortName(), e));
                                                activePlugins.remove(p);
                                                plugins.remove(p);
                                                throw e;
                                            }
                                        }
                                    });
                                }

                                // schedule execution of initializing plugins
                                for (final PluginWrapper p : activePlugins.toArray(new PluginWrapper[activePlugins.size()])) {
                                    g.followedBy().notFatal().attains(PLUGINS_STARTED).add("Initializing plugin " + p.getShortName(), new Executable() {
                                        public void run(Reactor session) throws Exception {
                                            try {
                                                p.getPlugin().postInitialize();
                                            } catch (Exception e) {
                                                failedPlugins.add(new FailedPlugin(p.getShortName(), e));
                                                activePlugins.remove(p);
                                                plugins.remove(p);
                                                throw e;
                                            }
                                        }
                                    });
                                }

                                // register them all
                                session.addAll(g.discoverTasks(session));
                            }
                        });

                        // register them all
                        session.addAll(g.discoverTasks(session));
                    }
                });
            }
        };
    }

    /**
     * If the war file has any "/WEB-INF/plugins/*.hpi", extract them into the plugin directory.
     *
     * @return
     *      File names of the bundled plugins. Like {"ssh-slaves.hpi","subvesrion.hpi"}
     */
    private Collection<String> loadBundledPlugins() {
        // this is used in tests, when we want to override the default bundled plugins with .hpl versions
        if (System.getProperty("hudson.bundled.plugins") != null) {
            return Collections.emptySet();
        }

        Set<String> names = new HashSet<String>();

        for( String path : Util.fixNull((Set<String>)context.getResourcePaths("/WEB-INF/plugins"))) {
            String fileName = path.substring(path.lastIndexOf('/')+1);
            if(fileName.length()==0) {
                // see http://www.nabble.com/404-Not-Found-error-when-clicking-on-help-td24508544.html
                // I suspect some containers are returning directory names.
                continue;
            }
            try {
                names.add(fileName);

                URL url = context.getResource(path);
                long lastModified = url.openConnection().getLastModified();
                File file = new File(rootDir, fileName);
                File pinFile = new File(rootDir, fileName+".pinned");

                // update file if:
                //  - no file exists today
                //  - bundled version and current version differs (by timestamp), and the file isn't pinned.
                if (!file.exists() || (file.lastModified() != lastModified && !pinFile.exists())) {
                    FileUtils.copyURLToFile(url, file);
                    file.setLastModified(url.openConnection().getLastModified());
                    // lastModified is set for two reasons:
                    // - to avoid unpacking as much as possible, but still do it on both upgrade and downgrade
                    // - to make sure the value is not changed after each restart, so we can avoid
                    // unpacking the plugin itself in ClassicPluginStrategy.explode
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to extract the bundled plugin "+fileName,e);
            }
        }

        return names;
    }

    /**
     * Creates a hudson.PluginStrategy, looking at the corresponding system property. 
     */
    private PluginStrategy createPluginStrategy() {
		String strategyName = System.getProperty(PluginStrategy.class.getName());
		if (strategyName != null) {
			try {
				Class<?> klazz = getClass().getClassLoader().loadClass(strategyName);
				Object strategy = klazz.getConstructor(PluginManager.class)
						.newInstance(this);
				if (strategy instanceof PluginStrategy) {
					LOGGER.info("Plugin strategy: " + strategyName);
					return (PluginStrategy) strategy;
				} else {
					LOGGER.warning("Plugin strategy (" + strategyName + 
							") is not an instance of hudson.PluginStrategy");
				}
			} catch (ClassNotFoundException e) {
				LOGGER.warning("Plugin strategy class not found: "
						+ strategyName);
			} catch (Exception e) {
				LOGGER.log(Level.WARNING, "Could not instantiate plugin strategy: "
						+ strategyName + ". Falling back to ClassicPluginStrategy", e);
			}
			LOGGER.info("Falling back to ClassicPluginStrategy");
		}
		
		// default and fallback
		return new ClassicPluginStrategy(this);
    }

    public PluginStrategy getPluginStrategy() {
        return strategy;
    }

    /**
     * Returns true if any new plugin was added, which means a restart is required
     * for the change to take effect.
     */
    public boolean isPluginUploaded() {
        return pluginUploaded;
    }
    
    public List<PluginWrapper> getPlugins() {
        return plugins;
    }

    public List<FailedPlugin> getFailedPlugins() {
        return failedPlugins;
    }

    public PluginWrapper getPlugin(String shortName) {
        for (PluginWrapper p : plugins) {
            if(p.getShortName().equals(shortName))
                return p;
        }
        return null;
    }

    /**
     * Get the plugin instance that implements a specific class, use to find your plugin singleton.
     * Note: beware the classloader fun.
     * @param pluginClazz The class that your plugin implements.
     * @return The plugin singleton or <code>null</code> if for some reason the plugin is not loaded.
     */
    public PluginWrapper getPlugin(Class<? extends Plugin> pluginClazz) {
        for (PluginWrapper p : plugins) {
            if(pluginClazz.isInstance(p.getPlugin()))
                return p;
        }
        return null;
    }

    /**
     * Get the plugin instances that extend a specific class, use to find similar plugins.
     * Note: beware the classloader fun.
     * @param pluginSuperclass The class that your plugin is derived from.
     * @return The list of plugins implementing the specified class.
     */
    public List<PluginWrapper> getPlugins(Class<? extends Plugin> pluginSuperclass) {
        List<PluginWrapper> result = new ArrayList<PluginWrapper>();
        for (PluginWrapper p : plugins) {
            if(pluginSuperclass.isInstance(p.getPlugin()))
                result.add(p);
        }
        return Collections.unmodifiableList(result);
    }

    public String getDisplayName() {
        return "Plugin Manager";
    }

    public String getSearchUrl() {
        return "pluginManager";
    }

    /**
     * Discover all the service provider implementations of the given class,
     * via <tt>META-INF/services</tt>.
     */
    public <T> Collection<Class<? extends T>> discover( Class<T> spi ) {
        Set<Class<? extends T>> result = new HashSet<Class<? extends T>>();

        for (PluginWrapper p : activePlugins) {
            Service.load(spi, p.classLoader, result);
        }

        return result;
    }

    /**
     * Orderly terminates all the plugins.
     */
    public void stop() {
        for (PluginWrapper p : activePlugins)
            p.stop();
        activePlugins.clear();
        // Work around a bug in commons-logging.
        // See http://www.szegedi.org/articles/memleak.html
        LogFactory.release(uberClassLoader);
    }

    public HttpResponse doUpdateSources(StaplerRequest req) throws IOException {
        Hudson.getInstance().checkPermission(Hudson.ADMINISTER);

        if (req.hasParameter("remove")) {
            UpdateCenter uc = Hudson.getInstance().getUpdateCenter();
            BulkChange bc = new BulkChange(uc);
            try {
                for (String id : req.getParameterValues("sources"))
                    uc.getSites().remove(uc.getById(id));
            } finally {
                bc.commit();
            }
        } else
        if (req.hasParameter("add"))
            return new HttpRedirect("addSite");

        return new HttpRedirect("./sites");
    }

    /**
     * Performs the installation of the plugins.
     */
    public void doInstall(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        Enumeration<String> en = req.getParameterNames();
        while (en.hasMoreElements()) {
            String n =  en.nextElement();
            if(n.startsWith("plugin.")) {
                n = n.substring(7);
                if (n.indexOf(".") > 0) {
                    String[] pluginInfo = n.split("\\.");
                    UpdateSite.Plugin p = Hudson.getInstance().getUpdateCenter().getById(pluginInfo[1]).getPlugin(pluginInfo[0]);
                    if(p==null)
                        throw new Failure("No such plugin: "+n);
                    p.deploy();
                }
            }
        }
        rsp.sendRedirect("../updateCenter/");
    }

    public void doProxyConfigure(
            @QueryParameter("proxy.server") String server,
            @QueryParameter("proxy.port") String port,
            @QueryParameter("proxy.userName") String userName,
            @QueryParameter("proxy.password") String password,
            StaplerResponse rsp) throws IOException {
        Hudson hudson = Hudson.getInstance();
        hudson.checkPermission(Hudson.ADMINISTER);

        server = Util.fixEmptyAndTrim(server);
        if(server==null) {
            hudson.proxy = null;
            ProxyConfiguration.getXmlFile().delete();
        } else {
            hudson.proxy = new ProxyConfiguration(server,Integer.parseInt(Util.fixEmptyAndTrim(port)),
                    Util.fixEmptyAndTrim(userName),Util.fixEmptyAndTrim(password));
            hudson.proxy.save();
        }
        rsp.sendRedirect("./advanced");
    }

    /**
     * Uploads a plugin.
     */
    public HttpResponse doUploadPlugin(StaplerRequest req) throws IOException, ServletException {
        try {
            Hudson.getInstance().checkPermission(Hudson.ADMINISTER);

            ServletFileUpload upload = new ServletFileUpload(new DiskFileItemFactory());

            // Parse the request
            FileItem fileItem = (FileItem) upload.parseRequest(req).get(0);
            String fileName = Util.getFileName(fileItem.getName());
            if(!fileName.endsWith(".hpi"))
                throw new Failure(hudson.model.Messages.Hudson_NotAPlugin(fileName));
            fileItem.write(new File(rootDir, fileName));
            fileItem.delete();

            pluginUploaded = true;

            return new HttpRedirect(".");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {// grrr. fileItem.write throws this
            throw new ServletException(e);
        }
    }

    /**
     * {@link ClassLoader} that can see all plugins.
     */
    private final class UberClassLoader extends ClassLoader {
        public UberClassLoader() {
            super(PluginManager.class.getClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            // first, use the context classloader so that plugins that are loading
            // can use its own classloader first.
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if(cl!=null && cl!=this)
                try {
                    return cl.loadClass(name);
                } catch(ClassNotFoundException e) {
                    // not found. try next
                }

            for (PluginWrapper p : activePlugins) {
                try {
                    return p.classLoader.loadClass(name);
                } catch (ClassNotFoundException e) {
                    //not found. try next
                }
            }
            // not found in any of the classloader. delegate.
            throw new ClassNotFoundException(name);
        }

        @Override
        protected URL findResource(String name) {
            for (PluginWrapper p : activePlugins) {
                URL url = p.classLoader.getResource(name);
                if(url!=null)
                    return url;
            }
            return null;
        }

        @Override
        protected Enumeration<URL> findResources(String name) throws IOException {
            List<URL> resources = new ArrayList<URL>();
            for (PluginWrapper p : activePlugins) {
                resources.addAll(Collections.list(p.classLoader.getResources(name)));
            }
            return Collections.enumeration(resources);
        }
    }

    private static final Logger LOGGER = Logger.getLogger(PluginManager.class.getName());

    /**
     * Remembers why a plugin failed to deploy.
     */
    public static final class FailedPlugin {
        public final String name;
        public final Exception cause;

        public FailedPlugin(String name, Exception cause) {
            this.name = name;
            this.cause = cause;
        }

        public String getExceptionString() {
            return Functions.printThrowable(cause);
        }
    }
}
