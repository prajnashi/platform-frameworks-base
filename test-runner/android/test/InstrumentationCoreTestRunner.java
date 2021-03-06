/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

import com.android.internal.util.Predicate;
import com.android.internal.util.Predicates;

import dalvik.annotation.BrokenTest;
import dalvik.annotation.SideEffect;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestListener;
import android.os.Bundle;
import android.test.suitebuilder.TestMethod;
import android.test.suitebuilder.annotation.HasAnnotation;
import android.util.Log;

/**
 * This test runner extends the default InstrumentationTestRunner. It overrides
 * the {@code onCreate(Bundle)} method and sets the system properties necessary
 * for many core tests to run. This is needed because there are some core tests
 * that need writing access to the file system. We also need to set the harness
 * Thread's context ClassLoader. Otherwise some classes and resources will not
 * be found. Finally, we add a means to free memory allocated by a TestCase
 * after its execution.
 *
 * @hide
 */
public class InstrumentationCoreTestRunner extends InstrumentationTestRunner {

    private static final String TAG = "InstrumentationCoreTestRunner";
    private boolean singleTest = false;
    
    @Override
    public void onCreate(Bundle arguments) {
        // We might want to move this to /sdcard, if is is mounted/writable.
        File cacheDir = getTargetContext().getCacheDir();

        System.setProperty("user.language", "en");
        System.setProperty("user.region", "US");
        
        System.setProperty("java.home", cacheDir.getAbsolutePath());
        System.setProperty("user.home", cacheDir.getAbsolutePath());
        System.setProperty("java.io.tmpdir", cacheDir.getAbsolutePath());
        System.setProperty("javax.net.ssl.trustStore",
                "/etc/security/cacerts.bks");
        
        if (arguments != null) {
            String classArg = arguments.getString(ARGUMENT_TEST_CLASS);
            singleTest = classArg != null && classArg.contains("#"); 
        }
        
        super.onCreate(arguments);
    }

    protected AndroidTestRunner getAndroidTestRunner() {
        AndroidTestRunner runner = super.getAndroidTestRunner();

        runner.addTestListener(new TestListener() {
            private Class<?> lastClass;
            
            public void startTest(Test test) {
                if (test.getClass() != lastClass) {
                    printMemory(test.getClass());
                }
                
                Thread.currentThread().setContextClassLoader(
                        test.getClass().getClassLoader());
            }
            
            public void endTest(Test test) {
                if (test instanceof TestCase) {
                    if (lastClass == null) {
                        lastClass = test.getClass();
                    } else {
                        if (test.getClass() != lastClass) {
                            cleanup(lastClass);
                            lastClass = test.getClass();
                        }
                    }
                }
            }
            
            public void addError(Test test, Throwable t) {
            }
            
            public void addFailure(Test test, AssertionFailedError t) {
            }
            
            /**
             * Dumps some memory info.
             */
            private void printMemory(Class<? extends Test> testClass) {
                Runtime runtime = Runtime.getRuntime();

                long total = runtime.totalMemory();
                long free = runtime.freeMemory();
                long used = total - free;
                
                Log.d(TAG, "Total memory  : " + total);
                Log.d(TAG, "Used memory   : " + used);
                Log.d(TAG, "Free memory   : " + free);
                Log.d(TAG, "Now executing : " + testClass.getName());
            }

            /**
             * Nulls all static reference fields in the given test class. This
             * method helps us with those test classes that don't have an
             * explicit tearDown() method. Normally the garbage collector should
             * take care of everything, but since JUnit keeps references to all
             * test cases, a little help might be a good idea.
             */
            private void cleanup(Class<?> clazz) {
                if (clazz != TestCase.class) {
                    Field[] fields = clazz.getDeclaredFields();
                    for (int i = 0; i < fields.length; i++) {
                        Field f = fields[i];
                        if (!f.getType().isPrimitive() &&
                                Modifier.isStatic(f.getModifiers())) {
                            try {
                                f.setAccessible(true);
                                f.set(null, null);
                            } catch (Exception ignored) {
                                // Nothing we can do about it.
                            }
                        }
                    }
                    
                    // don't cleanup the superclass for now
                    //cleanup(clazz.getSuperclass());
                }
            }
            
        });
        
        return runner;
    }    

    @Override
    List<Predicate<TestMethod>> getBuilderRequirements() {
        List<Predicate<TestMethod>> builderRequirements =
                super.getBuilderRequirements();
        Predicate<TestMethod> brokenTestPredicate =
                Predicates.not(new HasAnnotation(BrokenTest.class));
        builderRequirements.add(brokenTestPredicate);
        if (!singleTest) {
            Predicate<TestMethod> sideEffectPredicate =
                    Predicates.not(new HasAnnotation(SideEffect.class));
            builderRequirements.add(sideEffectPredicate);
        }
        return builderRequirements;
    }
}
