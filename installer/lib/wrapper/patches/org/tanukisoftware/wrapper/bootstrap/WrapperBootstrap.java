package org.tanukisoftware.wrapper.bootstrap;

/*
 * Copyright (c) 1999, 2023 Tanuki Software, Ltd.
 * http://www.tanukisoftware.com
 * All rights reserved.
 *
 * This software is the proprietary information of Tanuki Software.
 * You shall use it only in accordance with the terms of the
 * license agreement you entered into with Tanuki Software.
 * http://wrapper.tanukisoftware.com/doc/english/licenseOverview.html
 */

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;

import org.tanukisoftware.wrapper.WrapperInfo;

public class WrapperBootstrap
{
    private static boolean c_bootstrapInstance;
    
    private static final int ENTRYPOINT_MAINCLASS = 1;
  /*  private static final int ENTRYPOINT_MODULE    = 2;*/
    private static final int ENTRYPOINT_JAR       = 3;

    private static boolean m_debug;
    
    public static boolean isBootstrapInstance()
    {
        return c_bootstrapInstance;
    }
    
    /*---------------------------------------------------------------
     * Main Method
     *-------------------------------------------------------------*/
    /**
     * In general, this method should avoid failing with exit code 1 and
     *  instead print tokens that can be parsed by native code.
     *  Exit code 1 will cause to not parse the output at all, not even the
     *  version number.
     *  For some critical errors, it can be useful to print error messages or
     *  call stacks from this function and then return. This can be achieved
     *  with special tokens to inform the Wrapper to raise the loglevel.
     *
     * @param args arg1: 1: 'mainclass', 2: 'module' or 3: 'jar'
     *             arg2: name of the element which type is specified by arg1
     *             arg3: debug
     */
    public static void main( String args[] )
    {
        c_bootstrapInstance = true;

        HashMap tm = new LinkedHashMap();

        tm.put( "wrapper_version", WrapperInfo.getVersion() );
        
        if ( args.length < 3 )
        {
            // This output will cause the error after to be printed at loglevel ERROR.
            tm.put( "wrong_argument_number", Integer.toString( args.length ) );
            printParseInformation( tm );
            System.err.println( "WrapperBootstrap Error: Wrong argument number." );
            return;
        }

        try
        {
            if ( Integer.parseInt( args[2] ) != 0 )
            {
                m_debug = true;
            }
            else
            {
                m_debug = false;
            }
        }
        catch ( NumberFormatException e )
        {
            m_debug = false;
        }

        Class mainClassClass = null;

        boolean findMainModule = false;

        int javaVersion = getJavaVersion();

        int id = Integer.parseInt( args[0] );
        if ( id == ENTRYPOINT_MAINCLASS )
        {
            /**
             * Output:
             * ------------------------------
             *     mainclass: not found
             *   or (Java >= 9)
             *     mainmodule: not found
             *     package: <package>
             *   or (Java >= 9)
             *     mainmodule: <modulename>
             *     package: <package>
             *   or (Java >= 9)
             *     mainmodule: unnamed
             *     package: <package>
             */
            try
            {
                mainClassClass = Class.forName( args[1] );
            }
            catch ( ClassNotFoundException e )
            {
                tm.put( "mainclass", "not found" );
                mainClassClass = null;
            }
            catch ( Throwable t )
            {
                // This output will cause the stack below to be printed at loglevel ERROR.
                tm.put( "mainclass", "load failed" );
                printParseInformation( tm );
                t.printStackTrace();
                return;
            }
            if ( javaVersion >= 9 )
            {
                findMainModule = true;
            }
        }
     // else if ( id == ENTRYPOINT_MODULE )
     // {
     //     if ( javaVersion >= 9 )
     //     {
     //         /**
     //          * Output (Java >= 9):
     //          * ------------------------------
     //          *     mainmodule: not found
     //          *   or
     //          *     mainmodule: no main class
     //          *   or
     //          *     mainclass: not found
     //          *   or
     //          *     mainclass: <mainclass>
     //          *     package: <package>
     //          */
     //         Object module = getModuleByName( args[1] );
     //         if ( module != null )
     //         {
     //             Object mainClass = null;
     //             try
     //             {
     //                 Method m = module.getClass().getMethod( "getDescriptor", new Class[] { } );
     //                 Object descriptor = m.invoke( module, new Object[] { } );
     //                 if ( descriptor != null ) 
     //                 {
     //                     m = descriptor.getClass().getMethod( "mainClass", new Class[] { } );
     //                     Object optional = m.invoke( descriptor, new Object[] { } );
     //                     m = optional.getClass().getMethod( "get", new Class[] { });
     //                     try 
     //                     {
     //                         mainClass = m.invoke( optional, new Object[] { } );
     //                     }
     //                     catch ( InvocationTargetException e ) {
     //                         if ( m_debug && e.getCause() instanceof NoSuchElementException ) {
     //                             printDebug( "Failed to find main class." );
     //                         }
     //                     }
     //                     if ( mainClass != null )
     //                     {
     //                         tm.put( "mainclass", mainClass );
     //                     }
     //                     else
     //                     {
     //                         tm.put( "mainmodule", "no main class" );
     //                     }
     //                 }
     //                 else
     //                 {
     //                     // unnamed module (but should not happen because we retrieved the module by its name)
     //                 }
     //             }
     //             catch ( Exception e )
     //             {
     //                 if ( m_debug )
     //                 {
     //                     printDebug( "Failed to retrieve main class - " + e.getMessage() ); 
     //                 }
     //                 tm.put( "mainclass", "not found" );
     //             }
     //         }
     //         else
     //         {
     //             tm.put( "mainmodule", "not found" );
     //         }
     //     }
     // }
        else if ( id == ENTRYPOINT_JAR )
        {
            /**
             * Output:
             * ------------------------------
             *     mainjar: not found
             *   or
             *     mainjar: can't open
             *   or
             *     mainjar: no manifest
             *   or
             *     [
             *         mainjar: no mainclass
             *       or
             *         mainclass: not found - <classname>
             *       or
             *         mainclass: <classname>
             *         mainmodule: <mainmodule>
             *         package: <package>
             *     ]
             *     and
             *     [
             *         mainjar: no classpath
             *       or
             *         classpath: <classpath>
             *     ]
             */
            // Look for the specified jar file.
            File file = new File( args[1] );
            if ( !file.exists() )
            {
                tm.put( "mainjar", "not found" );
            }
            else
            {
                JarFile jarFile;
                try
                {
                    jarFile = new JarFile( file );
                }
                catch ( IOException e )
                {
                    if ( m_debug )
                    {
                        printDebug( "Can't open jar file - " + e.getMessage() ); 
                    }
                    tm.put( "mainjar", "can't open" );
                    jarFile = null;
                }
                catch ( SecurityException e )
                {
                    if ( m_debug )
                    {
                        printDebug( "Can't open jar file - " + e.getMessage() ); 
                    }
                    tm.put( "mainjar", "access denied" );
                    jarFile = null;
                }
                if ( jarFile != null )
                {
                    Manifest manifest;
                    try
                    {
                        manifest = jarFile.getManifest();
                    }
                    catch ( IOException e )
                    {
                        if ( m_debug )
                        {
                            printDebug( "Jar file doesn't have a manifest - " + e.getMessage() ); 
                        }
                        tm.put( "mainjar", "no manifest" );
                        manifest = null;
                    }
                    
                    if ( manifest != null )
                    {
                        Attributes attributes = manifest.getMainAttributes();

                        String mainClassName = attributes.getValue( "Main-Class" );
                        if ( mainClassName == null ) 
                        {
                            tm.put( "mainjar", "no mainclass" );
                        }
                        else
                        {
                            URLClassLoader cl;

                            // Store the main jar in the classpath.
                            try
                            {
                                URL[] classURLs = new URL[1];
                                classURLs[0] = new URL( "file:" + file.getAbsolutePath() );
                                cl = URLClassLoader.newInstance( classURLs, WrapperBootstrap.class.getClassLoader() );
                            }
                            catch ( MalformedURLException e )
                            {
                                if ( m_debug )
                                {
                                    printDebug( "Unable to add jar to classpath: " + e.getMessage() );
                                }
                                cl = null;
                            }
                            try
                            {
                                if ( cl == null )
                                {
                                    mainClassClass = Class.forName( mainClassName );
                                }
                                else
                                {
                                    mainClassClass = Class.forName( mainClassName, false, cl );
                                }
                                if ( javaVersion >= 9 )
                                {
                                    findMainModule = true;
                                }
                            }
                            catch ( ClassNotFoundException e )
                            {
                                if ( m_debug )
                                {
                                    printDebug( "Failed to retrieve main class - " + e.getMessage() ); 
                                }
                                mainClassClass = null;
                            }
                            catch ( Throwable t )
                            {
                                // This output will cause the stack below to be printed at loglevel ERROR.
                                tm.put( "mainclass", "load failed - " + mainClassName );
                                printParseInformation( tm );
                                t.printStackTrace();
                                return;
                            }

                            if ( mainClassClass != null )
                            {
                                tm.put( "mainclass", mainClassName );
                            }
                            else
                            {
                                tm.put( "mainclass", "not found - " + mainClassName );
                            }
                        }

                        String classPath = attributes.getValue( "Class-Path" );
                        if ( classPath == null )
                        {
                            tm.put( "mainjar", "no classpath" );
                        }
                        else
                        {
                            tm.put( "classpath", classPath );
                        }
                    }
                }
            }
        }
        else
        {
            // This output will cause the error after to be printed at loglevel ERROR.
            tm.put( "invalid_argument", "" );
            printParseInformation( tm );
            System.err.println( "WrapperBootstrap Error: Invalid argument(s)." );
            return;
        }

        if ( mainClassClass != null )
        {
            if ( findMainModule )
            {
                Object moduleName = null;
                try
                {
                    // use reflection as this requires java 9
                    Method m = Class.class.getMethod( "getModule", new Class[] { } );
                    Object module = m.invoke( mainClassClass, new Object[] { } );
                    m = module.getClass().getMethod( "getName", new Class[] { } );
                    moduleName = (String)m.invoke( module, new Object[] { } );
                    if ( moduleName != null )
                    {
                        tm.put( "mainmodule", moduleName );
                    }
                    else
                    {
                        tm.put( "mainmodule", "unnamed" );
                    }
                }
                catch ( Exception e )
                {
                    if ( m_debug )
                    {
                        printDebug( "Failed to retrieve main module - " + e.getMessage() ); 
                    }
                    tm.put( "mainmodule", "not found" );
                }
            }

            Package pkg = mainClassClass.getPackage();
            if ( pkg != null )
            {
                tm.put( "package", pkg.getName() );
            }
            else
            {
                // should not happen because the class was retrieved using the fully qualified name.
                tm.put( "package", "not found" );
            }
        }

        // check that modules used by the Wrapper are available (might not be the case if running with a custom image)
        if ( javaVersion >= 9 )
        {
            if ( getModuleByName( "java.management" ) == null )
            {
                tm.put( "java.management", "not available" );
            }
        }

        // TODO: Make sure that the method is public and static?

        printParseInformation( tm );
    }

    private static void printParseInformation( HashMap tm )
    {
        StringBuffer sb = new StringBuffer();
        for ( Iterator iter = tm.keySet().iterator(); iter.hasNext(); )
        {
            String name = (String)iter.next();
            sb.append( "WrapperBootstrap: " );
            sb.append( name );
            sb.append( ": " );
            sb.append( (String)tm.get( name ) );
            if ( iter.hasNext() )
            {
                sb.append( System.getProperty( "line.separator" ) );
            }
        }

        System.out.println( sb.toString() );
    }

    private static void printDebug( String message )
    {
        System.out.println( "WrapperBootstrap Debug: " + message );
    }

    private static int getJavaVersion()
    {
        String version = System.getProperty( "java.version" );
        if ( version.startsWith( "1." ) )
        {
            // Java 8 or lower
            version = version.substring( 2, 3 );
        }
        else
        {
            // Java 9 or higher
            int i = version.indexOf( "." );
            if ( i != -1 ) 
            {
                version = version.substring( 0, i );
            }
            // I2P 24-ea
            i = version.indexOf( "-" );
            if ( i != -1 ) 
            {
                version = version.substring( 0, i );
            }
        }
        return Integer.parseInt( version );
    }

    private static Object getModuleByName( String moduleName )
    {
        Object module = null;
        Class moduleLayerClass;
        try
        {
            moduleLayerClass = Class.forName( "java.lang.ModuleLayer" );
        }
        catch ( ClassNotFoundException e )
        {
            if ( m_debug )
            {
                printDebug( "java.lang.ModuleLayer not found" ); 
            }
            moduleLayerClass = null;
        }
        if ( moduleLayerClass != null )
        {
            try
            {
                Method m = moduleLayerClass.getMethod( "boot", new Class[] { } );
                Object boot = m.invoke( null, new Object[] { } );
                if ( boot != null )
                {
                    m = moduleLayerClass.getMethod( "findModule", new Class[] { String.class } );
                    Object optional = m.invoke( boot, new Object[] { moduleName } );
                    m = optional.getClass().getMethod( "get", new Class[] { });
                    try 
                    {
                        module = m.invoke( optional, new Object[] { } );
                    }
                    catch ( InvocationTargetException e ) {
                        if ( m_debug && e.getCause() instanceof NoSuchElementException ) {
                            printDebug( "Failed to find module." );
                        }
                    }
                }
                else
                {
                    // boot layer not initialized (should not happen)
                }
            }
            catch ( Exception e )
            {
                if ( m_debug )
                {
                    printDebug( "Failed to retrieve " + moduleName +  " module - " + e.getMessage() ); 
                }
            }
        }
        return module;
    }
}
