/*
 * Copyright 2008 JBoss Inc
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

package org.drools.core.factmodel;

import org.drools.core.factmodel.traits.Thing;
import org.drools.core.factmodel.traits.TraitTypeMap;
import org.drools.core.factmodel.traits.TraitableBean;
import org.drools.core.rule.builder.dialect.asm.ClassGenerator;
import org.mvel2.asm.*;

import java.beans.IntrospectionException;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A builder to dynamically build simple Javabean(TM) classes
 */
public class DefaultBeanClassBuilder implements Opcodes, BeanClassBuilder, Serializable {
    protected boolean     debug  = false;

    public DefaultBeanClassBuilder() {
        this( "true".equalsIgnoreCase( System.getProperty( "org.drools.classbuilder.debug" ) ) );
    }

    public DefaultBeanClassBuilder(final boolean debug) {
        this.debug = debug;
    }


    /**
     * Dynamically builds, defines and loads a class based on the given class definition
     *
     * @param classDef the class definition object structure
     *
     * @return the Class instance for the given class definition
     *
     * @throws IOException
     * @throws IntrospectionException
     * @throws InvocationTargetException
     * @throws IllegalAccessException
     * @throws NoSuchMethodException
     * @throws ClassNotFoundException
     * @throws IllegalArgumentException
     * @throws SecurityException
     * @throws NoSuchFieldException
     * @throws InstantiationException
     */
    public byte[] buildClass( ClassDefinition classDef ) throws IOException,
            IntrospectionException,
            SecurityException,
            IllegalArgumentException,
            ClassNotFoundException,
            NoSuchMethodException,
            IllegalAccessException,
            InvocationTargetException,
            InstantiationException,
            NoSuchFieldException {

        ClassWriter cw = new ClassWriter( ClassWriter.COMPUTE_MAXS );
        //ClassVisitor cw = new CheckClassAdapter(cwr);

        this.buildClassHeader( cw,
                classDef );

        this.buildFields( cw,
                classDef );

        if ( classDef.isTraitable() ) {
            this.buildDynamicPropertyMap( cw, classDef );
            this.buildTraitMap(cw, classDef);
        }

        this.buildConstructors( cw,
                classDef );

        this.buildGettersAndSetters( cw,
                classDef );

        this.buildEqualityMethods( cw,
                classDef );

        this.buildToString( cw,
                classDef );

        if ( classDef.isTraitable() ) {
            // must guarantee serialization order when enhancing fields are present
            this.buildSerializationMethods(cw,
                    classDef);
        }

        cw.visitEnd();

        return cw.toByteArray();
    }

    private void buildSerializationMethods(ClassWriter cw, ClassDefinition classDef) {
        MethodVisitor mv;
        {
            mv = cw.visitMethod(ACC_PUBLIC, "writeExternal", "(Ljava/io/ObjectOutput;)V", null, new String[]{"java/io/IOException"});
            mv.visitCode();

            for (  FieldDefinition field : classDef.getFieldsDefinitions() ) {

                mv.visitVarInsn( ALOAD, 1 );
                mv.visitVarInsn( ALOAD, 0 );
                visitFieldOrGetter( mv, classDef, field );
                mv.visitMethodInsn( INVOKEINTERFACE,
                        "java/io/ObjectOutput",
                        BuildUtils.serializationWriterName( field.getTypeName() ),
                        "(" + ( BuildUtils.isPrimitive( field.getTypeName() ) ?
                                BuildUtils.getTypeDescriptor( field.getTypeName() ) :
                                "Ljava/lang/Object;" ) + ")V");

            }

            mv.visitVarInsn( ALOAD, 1 );
            mv.visitVarInsn( ALOAD, 0 );
            mv.visitFieldInsn( Opcodes.GETFIELD,
                    BuildUtils.getInternalType( classDef.getClassName() ),
                    TraitableBean.MAP_FIELD_NAME,
                    Type.getDescriptor( Map.class ) );
            mv.visitMethodInsn( INVOKEINTERFACE,
                    "java/io/ObjectOutput",
                    "writeObject",
                    "(Ljava/lang/Object;)V" );

            mv.visitVarInsn( ALOAD, 1 );
            mv.visitVarInsn( ALOAD, 0 );
            mv.visitFieldInsn( Opcodes.GETFIELD,
                    BuildUtils.getInternalType( classDef.getClassName() ),
                    TraitableBean.TRAITSET_FIELD_NAME,
                    Type.getDescriptor( Map.class ) );
            mv.visitMethodInsn( INVOKEINTERFACE,
                    "java/io/ObjectOutput",
                    "writeObject",
                    "(Ljava/lang/Object;)V" );

            mv.visitInsn(RETURN);
            mv.visitMaxs( 0, 0 );
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(ACC_PUBLIC, "readExternal", "(Ljava/io/ObjectInput;)V", null, new String[]{"java/io/IOException", "java/lang/ClassNotFoundException"});
            mv.visitCode();

            for (  FieldDefinition field : classDef.getFieldsDefinitions() ) {
                mv.visitVarInsn( ALOAD, 0 );
                mv.visitVarInsn( ALOAD, 1 );
                mv.visitMethodInsn( INVOKEINTERFACE,
                        "java/io/ObjectInput",
                        BuildUtils.serializationReaderName( field.getTypeName() ),
                        "()" + ( BuildUtils.isPrimitive( field.getTypeName() ) ?
                                BuildUtils.getTypeDescriptor( field.getTypeName() ) :
                                "Ljava/lang/Object;" ) );
                if  ( !  BuildUtils.isPrimitive( field.getTypeName() ) ) {
                    mv.visitTypeInsn( CHECKCAST, BuildUtils.getInternalType( field.getTypeName() ) );
                }
                mv.visitMethodInsn( INVOKEVIRTUAL,
                        BuildUtils.getInternalType( classDef.getName() ),
                        BuildUtils.setterName( field.getName(), field.getTypeName() ),
                        "(" + BuildUtils.getTypeDescriptor( field.getTypeName() )+ ")V");
            }

            mv.visitVarInsn( ALOAD, 0 );
            mv.visitVarInsn( ALOAD, 1 );
            mv.visitMethodInsn( INVOKEINTERFACE,
                    "java/io/ObjectInput",
                    "readObject",
                    "()Ljava/lang/Object;");
            mv.visitTypeInsn( CHECKCAST, "java/util/Map" );
            mv.visitFieldInsn( Opcodes.PUTFIELD,
                    BuildUtils.getInternalType( classDef.getClassName() ),
                    TraitableBean.MAP_FIELD_NAME,
                    Type.getDescriptor( Map.class ) );
//
            mv.visitVarInsn( ALOAD, 0 );
            mv.visitVarInsn( ALOAD, 1 );
            mv.visitMethodInsn( INVOKEINTERFACE,
                    "java/io/ObjectInput",
                    "readObject",
                    "()Ljava/lang/Object;");
            mv.visitTypeInsn( CHECKCAST, "java/util/Map" );
            mv.visitFieldInsn( Opcodes.PUTFIELD,
                    BuildUtils.getInternalType( classDef.getClassName() ),
                    TraitableBean.TRAITSET_FIELD_NAME,
                    Type.getDescriptor( Map.class ) );


            mv.visitInsn( RETURN );
            mv.visitMaxs( 0, 0 );
            mv.visitEnd();
        }

    }

    protected void buildGettersAndSetters(ClassWriter cw, ClassDefinition classDef) {
        // Building methods
        for ( FieldDefinition fieldDef : classDef.getFieldsDefinitions() ) {
            if (! fieldDef.isInherited()) {
                this.buildGetMethod( cw,
                        classDef,
                        fieldDef );
                this.buildSetMethod( cw,
                        classDef,
                        fieldDef );
            }
        }
    }

    protected void buildEqualityMethods(ClassWriter cw, ClassDefinition classDef) {
        boolean hasKey = false;
        for ( FieldDefinition fld : classDef.getFieldsDefinitions() ) {
            if ( fld.isKey() ) {
                hasKey = true;
                break;
            }
        }

        if (hasKey) {
            this.buildEquals( cw,
                    classDef );
            this.buildHashCode( cw,
                    classDef );
        }
    }

    protected void buildFields(ClassWriter cw, ClassDefinition classDef) {
        // Building fields
        for ( FieldDefinition fieldDef : classDef.getFieldsDefinitions() ) {
            if (! fieldDef.isInherited())
                this.buildField( cw,
                        fieldDef );
        }
    }


    protected void buildConstructors(ClassWriter cw, ClassDefinition classDef) {
        // Building default constructor
        try {
            this.buildDefaultConstructor( cw,
                    classDef );
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Building constructor with all fields
        if (classDef.getFieldsDefinitions().size() > 0 && classDef.getFieldsDefinitions().size() < 120) {
            this.buildConstructorWithFields( cw,
                    classDef,
                    classDef.getFieldsDefinitions() );
        }

        // Building constructor with key fields only
        List<FieldDefinition> keys = new LinkedList<FieldDefinition>();
        for ( FieldDefinition fieldDef : classDef.getFieldsDefinitions() ) {
            if ( fieldDef.isKey() ) {
                keys.add( fieldDef );
            }
        }
        if ( !keys.isEmpty() && keys.size() != classDef.getFieldsDefinitions().size() ) {
            this.buildConstructorWithFields( cw,
                    classDef,
                    keys );
        }
    }


    /**
     * A traitable class is a special class with support for dynamic properties and types.
     *
     * This method builds the trait map, containing the references to the proxies
     * for each trait carried by an object at a given time.
     *
     * @param cw
     * @param classDef
     */
    protected void buildTraitMap(ClassWriter cw, ClassDefinition classDef) {

        FieldVisitor fv = cw.visitField( ACC_PRIVATE,
                TraitableBean.TRAITSET_FIELD_NAME,
                Type.getDescriptor( Map.class ),
                "Ljava/util/Map<Ljava/lang/String;Lorg/drools/core/factmodel/traits/Thing;>;",
                null );
        fv.visitEnd();

        MethodVisitor mv;

        mv = cw.visitMethod( ACC_PUBLIC,
                "_getTraitMap",
                Type.getMethodDescriptor( Type.getType( Map.class ), new Type[] {} ),
                "()Ljava/util/Map<Ljava/lang/String;Lorg/drools/core/factmodel/traits/Thing;>;",
                null );
        mv.visitCode();
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitFieldInsn( GETFIELD,
                           BuildUtils.getInternalType( classDef.getName() ),
                           TraitableBean.TRAITSET_FIELD_NAME,
                           Type.getDescriptor( Map.class ) );
        Label l2 = new Label();
        mv.visitJumpInsn( IFNULL, l2 );
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitFieldInsn( GETFIELD,
                           BuildUtils.getInternalType( classDef.getName() ),
                           TraitableBean.TRAITSET_FIELD_NAME,
                           Type.getDescriptor( Map.class ) );
        Label l1 = new Label();
        mv.visitJumpInsn( GOTO, l1 );
        mv.visitLabel( l2 );
        mv.visitMethodInsn( INVOKESTATIC,
                            Type.getInternalName( Collections.class ),
                            "emptyMap",
                            Type.getMethodDescriptor( Type.getType( Map.class ), new Type[] {} ) );

        mv.visitVarInsn( ALOAD, 0 );
        mv.visitTypeInsn( NEW, Type.getInternalName( TraitTypeMap.class ) );
        mv.visitInsn( DUP );
        mv.visitTypeInsn( NEW, Type.getInternalName( HashMap.class ) );
        mv.visitInsn( DUP );
        mv.visitMethodInsn( INVOKESPECIAL, Type.getInternalName( HashMap.class ), "<init>", "()V" );
        mv.visitMethodInsn( INVOKESPECIAL,
                            Type.getInternalName( TraitTypeMap.class ),
                            "<init>",
                            "(" + Type.getDescriptor( Map.class ) + ")V" );
        mv.visitFieldInsn( PUTFIELD,
                           BuildUtils.getInternalType( classDef.getName() ),
                           TraitableBean.TRAITSET_FIELD_NAME,
                           Type.getDescriptor( Map.class ) );


        mv.visitLabel( l1 );
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitFieldInsn( GETFIELD,
                           BuildUtils.getInternalType( classDef.getName() ),
                           TraitableBean.TRAITSET_FIELD_NAME,
                           Type.getDescriptor( Map.class ) );
        mv.visitInsn(ARETURN);
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();


        mv = cw.visitMethod( ACC_PUBLIC, "_setTraitMap", Type.getMethodDescriptor( Type.getType( void.class ), new Type[] { Type.getType( Map.class ) } ), null, null);
        mv.visitCode();
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitVarInsn( ALOAD, 1 );
        mv.visitFieldInsn( PUTFIELD, BuildUtils.getInternalType( classDef.getName() ), TraitableBean.TRAITSET_FIELD_NAME, Type.getDescriptor( Map.class ));
        mv.visitInsn( RETURN );
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();


        mv = cw.visitMethod( ACC_PUBLIC, "addTrait",
                Type.getMethodDescriptor( Type.getType( void.class ), new Type[] { Type.getType( String.class ), Type.getType( Thing.class ) } ),
                "(Ljava/lang/String;Lorg/drools/core/factmodel/traits/Thing;)V", null );
        mv.visitCode();
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitMethodInsn( INVOKEVIRTUAL,
                BuildUtils.getInternalType( classDef.getName() ),
                "_getTraitMap",
                Type.getMethodDescriptor( Type.getType( Map.class ), new Type[] {} ) );
        mv.visitVarInsn( ALOAD, 1 );
        mv.visitVarInsn( ALOAD, 2 );
        mv.visitMethodInsn( INVOKEINTERFACE,
                Type.getInternalName( Map.class ),
                "put",
                Type.getMethodDescriptor( Type.getType( Object.class ), new Type[] { Type.getType( Object.class ), Type.getType( Object.class ) } ) );
        mv.visitInsn( POP );
        mv.visitInsn( RETURN );
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();


        mv = cw.visitMethod( ACC_PUBLIC,
                "getTrait",
                Type.getMethodDescriptor( Type.getType( Thing.class ), new Type[] { Type.getType( String.class ) } ),
                Type.getMethodDescriptor( Type.getType( Thing.class ), new Type[] { Type.getType( String.class ) } ),
                null );
        mv.visitCode();
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitMethodInsn( INVOKEVIRTUAL,
                BuildUtils.getInternalType( classDef.getName() ),
                "_getTraitMap",
                Type.getMethodDescriptor( Type.getType( Map.class ), new Type[] {} ) );
        mv.visitVarInsn( ALOAD, 1 );
        mv.visitMethodInsn( INVOKEINTERFACE,
                Type.getInternalName( Map.class ),
                "get",
                Type.getMethodDescriptor( Type.getType( Object.class ), new Type[] { Type.getType( Object.class ) } ) );
        mv.visitTypeInsn( CHECKCAST, Type.getInternalName( Thing.class ) );
        mv.visitInsn( ARETURN );
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();


        mv = cw.visitMethod( ACC_PUBLIC,
                "hasTrait",
                Type.getMethodDescriptor( Type.getType( boolean.class ), new Type[] { Type.getType( String.class ) } ),
                null,
                null );
        mv.visitCode();
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitMethodInsn( INVOKEVIRTUAL,
                BuildUtils.getInternalType( classDef.getName() ),
                "_getTraitMap",
                Type.getMethodDescriptor( Type.getType( Map.class ), new Type[] {} ) );
        Label l0 = new Label();
        mv.visitJumpInsn( IFNONNULL, l0 );
        mv.visitInsn( ICONST_0 );
        mv.visitInsn( IRETURN );
        mv.visitLabel( l0 );
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitMethodInsn( INVOKEVIRTUAL,
                BuildUtils.getInternalType( classDef.getName() ),
                "_getTraitMap",
                Type.getMethodDescriptor( Type.getType( Map.class ), new Type[] {} ) );
        mv.visitVarInsn( ALOAD, 1 );
        mv.visitMethodInsn( INVOKEINTERFACE,
                Type.getInternalName( Map.class ),
                "containsKey",
                Type.getMethodDescriptor( Type.getType( boolean.class ), new Type[] { Type.getType( Object.class ) } ) );
        mv.visitInsn( IRETURN );
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();


        mv = cw.visitMethod( ACC_PUBLIC, "removeTrait",
                Type.getMethodDescriptor( Type.getType( Collection.class ), new Type[] { Type.getType( String.class ) } ),
                Type.getMethodDescriptor( Type.getType( Collection.class ), new Type[] { Type.getType( String.class ) } ),
                null );
        mv.visitCode();
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitMethodInsn( INVOKEVIRTUAL, BuildUtils.getInternalType( classDef.getName() ), "_getTraitMap", Type.getMethodDescriptor( Type.getType( Map.class ), new Type[] {} ) );
        mv.visitTypeInsn( CHECKCAST, Type.getInternalName( TraitTypeMap.class ) );
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn( INVOKEVIRTUAL, Type.getInternalName( TraitTypeMap.class ), "removeCascade",
                Type.getMethodDescriptor( Type.getType( Collection.class ), new Type[] { Type.getType( String.class )} ) );
        mv.visitInsn( ARETURN );
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();

        mv = cw.visitMethod( ACC_PUBLIC, "removeTrait",
                Type.getMethodDescriptor( Type.getType( Collection.class ), new Type[] { Type.getType( BitSet.class ) } ),
                Type.getMethodDescriptor( Type.getType( Collection.class ), new Type[] { Type.getType( BitSet.class ) } ),
                null );
        mv.visitCode();
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitMethodInsn( INVOKEVIRTUAL, BuildUtils.getInternalType( classDef.getName() ), "_getTraitMap", Type.getMethodDescriptor( Type.getType( Map.class ), new Type[] {} ) );
        mv.visitTypeInsn( CHECKCAST, Type.getInternalName( TraitTypeMap.class ) );
        mv.visitVarInsn( ALOAD, 1 );
        mv.visitMethodInsn( INVOKEVIRTUAL, Type.getInternalName( TraitTypeMap.class ), "removeCascade",
                Type.getMethodDescriptor( Type.getType( Collection.class ), new Type[] { Type.getType( BitSet.class )} ) );
        mv.visitInsn( ARETURN );
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();


        mv = cw.visitMethod( ACC_PUBLIC,
                "getTraits",
                Type.getMethodDescriptor( Type.getType( Collection.class ), new Type[] { } ),
                "()Ljava/util/Collection<Ljava/lang/String;>;",
                null );
        mv.visitCode();
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitMethodInsn( INVOKEVIRTUAL,
                BuildUtils.getInternalType( classDef.getName() ),
                "_getTraitMap",
                Type.getMethodDescriptor( Type.getType( Map.class ), new Type[] {} ) );
        mv.visitMethodInsn( INVOKEINTERFACE,
                Type.getInternalName( Map.class ),
                "keySet",
                Type.getMethodDescriptor( Type.getType( Set.class ), new Type[] {} ) );
        mv.visitInsn( ARETURN );
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();


        mv = cw.visitMethod( ACC_PUBLIC,
                "_setBottomTypeCode",
                Type.getMethodDescriptor( Type.getType( void.class ), new Type[] { Type.getType( BitSet.class ) } ),
                null, null );
        mv.visitCode();
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitFieldInsn( GETFIELD, BuildUtils.getInternalType( classDef.getName() ), TraitableBean.TRAITSET_FIELD_NAME , Type.getDescriptor( Map.class ) );
        mv.visitTypeInsn( CHECKCAST, Type.getInternalName( TraitTypeMap.class ) );
        mv.visitVarInsn( ALOAD, 1 );
        mv.visitMethodInsn( INVOKEVIRTUAL,
                Type.getInternalName( TraitTypeMap.class ),
                "setBottomCode",
                Type.getMethodDescriptor( Type.getType( void.class ), new Type[] { Type.getType( BitSet.class ) } ) );
        mv.visitInsn( RETURN );
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();

        mv = cw.visitMethod( ACC_PUBLIC,
                "getMostSpecificTraits",
                Type.getMethodDescriptor( Type.getType( Collection.class ), new Type[] { } ) ,
                "()Ljava/util/Collection<Lorg/drools/core/factmodel/traits/Thing;>;",
                null );
        mv.visitCode();
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitFieldInsn( GETFIELD, BuildUtils.getInternalType( classDef.getName() ),
                TraitableBean.TRAITSET_FIELD_NAME ,
                Type.getDescriptor( Map.class ) );
        mv.visitTypeInsn( CHECKCAST, Type.getInternalName( TraitTypeMap.class ) );
        mv.visitMethodInsn( INVOKEVIRTUAL,
                Type.getInternalName( TraitTypeMap.class ),
                "getMostSpecificTraits",
                Type.getMethodDescriptor( Type.getType( Collection.class ), new Type[] { } )  );
        mv.visitInsn( ARETURN );
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();

        mv = cw.visitMethod( ACC_PUBLIC, "getCurrentTypeCode", Type.getMethodDescriptor( Type.getType( BitSet.class ), new Type[] { } ) , null, null );
        mv.visitCode();
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitFieldInsn( GETFIELD,
                           BuildUtils.getInternalType( classDef.getName() ),
                           TraitableBean.TRAITSET_FIELD_NAME,
                           Type.getDescriptor( Map.class ) );
        Label l3 = new Label();
        mv.visitJumpInsn( IFNONNULL, l3 );
        mv.visitInsn( ACONST_NULL );
        mv.visitInsn( ARETURN );
        mv.visitLabel( l3 );
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitFieldInsn( GETFIELD,
                           BuildUtils.getInternalType( classDef.getName() ),
                           TraitableBean.TRAITSET_FIELD_NAME,
                           Type.getDescriptor( Map.class ) );
        mv.visitTypeInsn( CHECKCAST, Type.getInternalName( TraitTypeMap.class ) );
        mv.visitMethodInsn( INVOKEVIRTUAL,
                            Type.getInternalName( TraitTypeMap.class ),
                            "getCurrentTypeCode",
                            Type.getMethodDescriptor( Type.getType( BitSet.class ), new Type[] { } ) );
        mv.visitInsn( ARETURN );
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();


    }


    /**
     * A traitable class is a special class with support for dynamic properties and types.
     *
     * This method builds the property map, containing the key/values pairs to implement
     * any property defined in a trait interface but not supported by the traited class
     * fields.
     *
     * @param cw
     * @param def
     */
    protected void buildDynamicPropertyMap( ClassWriter cw, ClassDefinition def ) {

        FieldVisitor fv = cw.visitField( Opcodes.ACC_PRIVATE,
                TraitableBean.MAP_FIELD_NAME,
                Type.getDescriptor( Map.class ) ,
                "Ljava/util/Map<Ljava/lang/String;Ljava/lang/Object;>;",
                null);
        fv.visitEnd();

        MethodVisitor mv = cw.visitMethod( Opcodes.ACC_PUBLIC,
                "_getDynamicProperties",
                Type.getMethodDescriptor( Type.getType( Map.class ), new Type[] {} ),
                "()Ljava/util/Map<Ljava/lang/String;Ljava/lang/Object;>;",
                null);
        mv.visitCode();
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitFieldInsn( GETFIELD, BuildUtils.getInternalType(def.getName()), TraitableBean.MAP_FIELD_NAME, Type.getDescriptor( Map.class ) );
        mv.visitInsn( ARETURN );
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();



        mv = cw.visitMethod( ACC_PUBLIC,
                "_setDynamicProperties",
                Type.getMethodDescriptor( Type.getType( void.class ), new Type[] { Type.getType( Map.class ) } ),
                "(Ljava/util/Map<Ljava/lang/String;Ljava/lang/Object;>;)V",
                null);
        mv.visitCode();
        mv.visitVarInsn( ALOAD, 0 );
        mv.visitVarInsn( ALOAD, 1 );
        mv.visitFieldInsn ( PUTFIELD, BuildUtils.getInternalType( def.getName() ), TraitableBean.MAP_FIELD_NAME, Type.getDescriptor( Map.class ) );
        mv.visitInsn( RETURN) ;
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();

    }

    /**
     * Defines the class header for the given class definition
     *
     * @param cw
     * @param classDef
     */
    protected void buildClassHeader(ClassVisitor cw,
                                    ClassDefinition classDef) {
        String[] original = classDef.getInterfaces();
        String[] interfaces = new String[original.length];
        for ( int i = 0; i < original.length; i++ ) {
            interfaces[i] = BuildUtils.getInternalType( original[i] );
        }

        int classModifiers = Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER;
        if ( classDef.isAbstrakt() ) {
            classModifiers += Opcodes.ACC_ABSTRACT;
        }
        // Building class header
        cw.visit( ClassGenerator.JAVA_VERSION,
                classModifiers,
                BuildUtils.getInternalType( classDef.getClassName() ),
                null,
                BuildUtils.getInternalType( classDef.getSuperClass() ),
                interfaces );

        buildClassAnnotations(classDef, cw);



        cw.visitSource( classDef.getClassName() + ".java",
                null );
    }



    /**
     * Creates the field defined by the given FieldDefinition
     *
     * @param cw
     * @param fieldDef
     */
    protected void buildField(ClassVisitor cw,
                              FieldDefinition fieldDef) {
        FieldVisitor fv = cw.visitField(Opcodes.ACC_PRIVATE,
                fieldDef.getName(),
                BuildUtils.getTypeDescriptor(fieldDef.getTypeName()),
                null,
                null);


        buildFieldAnnotations(fieldDef, fv);

        fv.visitEnd();
    }



    /**
     * Creates a default constructor for the class
     *
     * @param cw
     */
    protected void buildDefaultConstructor(ClassVisitor cw,
                                           ClassDefinition classDef) {


        MethodVisitor mv = cw.visitMethod( Opcodes.ACC_PUBLIC,
                "<init>",
                Type.getMethodDescriptor( Type.VOID_TYPE,
                        new Type[]{} ),
                null,
                null );
        mv.visitCode();

        Label l0 = null;
        if ( this.debug ) {
            l0 = new Label();
            mv.visitLabel( l0 );
        }

        boolean hasObjects = defaultConstructorStart( mv, classDef );

        mv.visitInsn(Opcodes.RETURN);
        Label l1 = null;
        if ( this.debug ) {
            l1 = new Label();
            mv.visitLabel( l1 );
            mv.visitLocalVariable( "this",
                    BuildUtils.getTypeDescriptor( classDef.getClassName() ),
                    null,
                    l0,
                    l1,
                    0 );
        }
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();

    }


    protected boolean defaultConstructorStart( MethodVisitor mv, ClassDefinition classDef ) {
        // Building default constructor

        mv.visitVarInsn( Opcodes.ALOAD,
                0 );

        String sup = "";
        try {
            sup = Type.getInternalName(Class.forName(classDef.getSuperClass()));
        } catch (ClassNotFoundException e) {
            sup = BuildUtils.getInternalType( classDef.getSuperClass() );
        }
        mv.visitMethodInsn( Opcodes.INVOKESPECIAL,
                sup,
                "<init>",
                Type.getMethodDescriptor( Type.VOID_TYPE,
                        new Type[]{} ) );

        boolean hasObjects = false;
        for (FieldDefinition field : classDef.getFieldsDefinitions()) {

            hasObjects = hasObjects || initFieldWithDefaultValue( mv, classDef, field );
        }


        if ( classDef.isTraitable() ) {
            initializeDynamicTypeStructures( mv, classDef );
        }

        return hasObjects;
    }


    protected boolean initFieldWithDefaultValue( MethodVisitor mv, ClassDefinition classDef, FieldDefinition field ) {
        if ( field.getInitExpr() == null && field.isInherited() ) {
            return false;
        }
        // get simple init expression value
        Object val = BuildUtils.getDefaultValue(field);
        boolean hasObjects = false;

        if (val != null) {
            // there's a simple init expression
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            if ( BuildUtils.isPrimitive( field.getTypeName() )
                    || BuildUtils.isBoxed( field.getTypeName() )
                    || String.class.getName().equals( field.getTypeName() ) ) {
                mv.visitLdcInsn(val);
                if ( BuildUtils.isBoxed(field.getTypeName()) ) {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            BuildUtils.getInternalType(field.getTypeName()),
                            "valueOf",
                            "("+BuildUtils.unBox(field.getTypeName())+")"+BuildUtils.getTypeDescriptor(field.getTypeName()));
                }
            } else {
                hasObjects = true;
                String type = BuildUtils.getInternalType( val.getClass().getName() );
                mv.visitTypeInsn( NEW, type );
                mv.visitInsn(DUP);
                mv.visitMethodInsn( INVOKESPECIAL,
                        type,
                        "<init>",
                        "()V");
            }
        } else {
            // there's a complex init expression
            if ( field.getInitExpr() != null ) {
                mv.visitVarInsn( ALOAD, 0 );
                mv.visitLdcInsn( field.getInitExpr() );
                mv.visitMethodInsn( INVOKESTATIC,
                        "org/mvel2/MVEL",
                        "eval",
                        "(Ljava/lang/String;)Ljava/lang/Object;");
                mv.visitTypeInsn( CHECKCAST, BuildUtils.getInternalType( field.getTypeName() ) );
                val = field.getInitExpr();
            }
        }


        if ( val != null ) {
            if (! field.isInherited()) {
                mv.visitFieldInsn( Opcodes.PUTFIELD,
                        BuildUtils.getInternalType( classDef.getClassName() ),
                        field.getName(),
                        BuildUtils.getTypeDescriptor( field.getTypeName() ) );
            } else {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        BuildUtils.getInternalType( classDef.getClassName() ),
                        field.getWriteMethod(),
                        Type.getMethodDescriptor(Type.VOID_TYPE,
                                new Type[]{Type.getType(BuildUtils.getTypeDescriptor(field.getTypeName()))}
                        ));
            }

        }

        return hasObjects;
    }


    /**
     * Initializes the trait map and dynamic property map to empty values
     * @param mv
     * @param classDef
     */
    protected void initializeDynamicTypeStructures( MethodVisitor mv, ClassDefinition classDef) {

    }

    /**
     * Creates a constructor that takes and assigns values to all
     * fields in the order they are declared.
     *
     * @param cw
     * @param classDef
     */
    protected void buildConstructorWithFields(ClassVisitor cw,
                                              ClassDefinition classDef,
                                              Collection<FieldDefinition> fieldDefs) {


        Type[] params = new Type[fieldDefs.size()];
        int index = 0;
        for ( FieldDefinition field : fieldDefs ) {
            params[index++] = Type.getType( BuildUtils.getTypeDescriptor( field.getTypeName() ) );
        }

        MethodVisitor mv = cw.visitMethod( Opcodes.ACC_PUBLIC,
                "<init>",
                Type.getMethodDescriptor( Type.VOID_TYPE,
                        params ),
                null,
                null );
        mv.visitCode();
        Label l0 = null;
        if ( this.debug ) {
            l0 = new Label();
            mv.visitLabel( l0 );
        }

        fieldConstructorStart( mv, classDef, fieldDefs );

        mv.visitInsn( Opcodes.RETURN );
        Label l1 = null;
        if ( this.debug ) {
            l1 = new Label();
            mv.visitLabel( l1 );
            mv.visitLocalVariable( "this",
                    BuildUtils.getTypeDescriptor( classDef.getClassName() ),
                    null,
                    l0,
                    l1,
                    0 );
            for ( FieldDefinition field : classDef.getFieldsDefinitions() ) {
                Label l11 = new Label();
                mv.visitLabel( l11 );
                mv.visitLocalVariable( field.getName(),
                        BuildUtils.getTypeDescriptor( field.getTypeName() ),
                        null,
                        l0,
                        l1,
                        0 );
            }
        }
        mv.visitMaxs( 0,
                0 );
        mv.visitEnd();

    }

    protected void fieldConstructorStart(MethodVisitor mv, ClassDefinition classDef, Collection<FieldDefinition> fieldDefs) {
        mv.visitVarInsn( Opcodes.ALOAD,
                0 );

        String sup = "";
        try {
            sup = Type.getInternalName(Class.forName(classDef.getSuperClass()));
        } catch (ClassNotFoundException e) {
            sup = BuildUtils.getInternalType( classDef.getSuperClass() );
        }

        mv.visitMethodInsn( Opcodes.INVOKESPECIAL,
                sup,
                "<init>",
                Type.getMethodDescriptor( Type.VOID_TYPE,
                        new Type[]{} ) );

        int index = 1; // local vars start at 1, as 0 is "this"
        for ( FieldDefinition field : fieldDefs ) {
            if ( this.debug ) {
                Label l11 = new Label();
                mv.visitLabel( l11 );
            }
            mv.visitVarInsn( Opcodes.ALOAD,
                    0 );
            mv.visitVarInsn( Type.getType( BuildUtils.getTypeDescriptor( field.getTypeName() ) ).getOpcode( Opcodes.ILOAD ),
                    index++ );
            if ( field.getTypeName().equals( "long" ) || field.getTypeName().equals( "double" ) ) {
                // long and double variables use 2 words on the variables table
                index++;
            }

            if (! field.isInherited()) {
                mv.visitFieldInsn( Opcodes.PUTFIELD,
                        BuildUtils.getInternalType( classDef.getClassName() ),
                        field.getName(),
                        BuildUtils.getTypeDescriptor( field.getTypeName() ) );
            } else {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        BuildUtils.getInternalType(classDef.getClassName()),
                        field.getWriteMethod(),
                        Type.getMethodDescriptor(Type.VOID_TYPE,
                                new Type[]{Type.getType(BuildUtils.getTypeDescriptor(field.getTypeName()))}
                        ));
            }

        }



        for ( FieldDefinition field : classDef.getFieldsDefinitions() ) {
            if ( ! fieldDefs.contains( field ) && field.getInitExpr() != null && ! "".equals( field.getInitExpr().trim() ) ) {

                initFieldWithDefaultValue( mv, classDef, field );

            }
        }

        if ( classDef.isTraitable() ) {
            initializeDynamicTypeStructures( mv, classDef );
        }

    }




    /**
     * Creates the set method for the given field definition
     *
     * @param cw
     * @param classDef
     * @param fieldDef
     */
    protected void buildSetMethod(ClassVisitor cw,
                                  ClassDefinition classDef,
                                  FieldDefinition fieldDef) {
        MethodVisitor mv;
        // set method
        {
            mv = cw.visitMethod( Opcodes.ACC_PUBLIC,
                    fieldDef.getWriteMethod(),
                    Type.getMethodDescriptor( Type.VOID_TYPE,
                            new Type[]{Type.getType( BuildUtils.getTypeDescriptor( fieldDef.getTypeName() ) )} ),
                    null,
                    null );
            mv.visitCode();
            Label l0 = null;
            if ( this.debug ) {
                l0 = new Label();
                mv.visitLabel( l0 );
            }
            mv.visitVarInsn( Opcodes.ALOAD,
                    0 );
            mv.visitVarInsn( Type.getType( BuildUtils.getTypeDescriptor( fieldDef.getTypeName() ) ).getOpcode( Opcodes.ILOAD ),
                    1 );
            mv.visitFieldInsn( Opcodes.PUTFIELD,
                    BuildUtils.getInternalType( classDef.getClassName() ),
                    fieldDef.getName(),
                    BuildUtils.getTypeDescriptor( fieldDef.getTypeName() ) );

            mv.visitInsn( Opcodes.RETURN );
            Label l1 = null;
            if ( this.debug ) {
                l1 = new Label();
                mv.visitLabel( l1 );
                mv.visitLocalVariable( "this",
                        BuildUtils.getTypeDescriptor( classDef.getClassName() ),
                        null,
                        l0,
                        l1,
                        0 );
            }
            mv.visitMaxs( 0,
                    0 );
            mv.visitEnd();
        }
    }

    /**
     * Creates the get method for the given field definition
     *
     * @param cw
     * @param classDef
     * @param fieldDef
     */
    protected void buildGetMethod(ClassVisitor cw,
                                  ClassDefinition classDef,
                                  FieldDefinition fieldDef) {
        MethodVisitor mv;
        // Get method
        {
            mv = cw.visitMethod( Opcodes.ACC_PUBLIC,
                    fieldDef.getReadMethod(),
                    Type.getMethodDescriptor( Type.getType( BuildUtils.getTypeDescriptor( fieldDef.getTypeName() ) ),
                            new Type[]{} ),
                    null,
                    null );
            mv.visitCode();
            Label l0 = null;
            if ( this.debug ) {
                l0 = new Label();
                mv.visitLabel( l0 );
            }
            mv.visitVarInsn( Opcodes.ALOAD,
                    0 );
            mv.visitFieldInsn( Opcodes.GETFIELD,
                    BuildUtils.getInternalType( classDef.getClassName() ),
                    fieldDef.getName(),
                    BuildUtils.getTypeDescriptor( fieldDef.getTypeName() ) );
            mv.visitInsn( Type.getType( BuildUtils.getTypeDescriptor( fieldDef.getTypeName() ) ).getOpcode( Opcodes.IRETURN ) );
            Label l1 = null;
            if ( this.debug ) {
                l1 = new Label();
                mv.visitLabel( l1 );
                mv.visitLocalVariable( "this",
                        BuildUtils.getTypeDescriptor( classDef.getClassName() ),
                        null,
                        l0,
                        l1,
                        0 );
            }
            mv.visitMaxs( 0,
                    0 );
            mv.visitEnd();
        }
    }

    protected void buildEquals(ClassVisitor cw,
                               ClassDefinition classDef) {
        MethodVisitor mv;
        // Building equals method
        {
            mv = cw.visitMethod( Opcodes.ACC_PUBLIC,
                    "equals",
                    "(Ljava/lang/Object;)Z",
                    null,
                    null );
            mv.visitCode();
            Label l0 = null;
            if ( this.debug ) {
                l0 = new Label();
                mv.visitLabel( l0 );
            }

            // if ( this == obj ) return true;
            mv.visitVarInsn( Opcodes.ALOAD,
                    0 );
            mv.visitVarInsn( Opcodes.ALOAD,
                    1 );
            Label l1 = new Label();
            mv.visitJumpInsn( Opcodes.IF_ACMPNE,
                    l1 );
            mv.visitInsn( Opcodes.ICONST_1 );
            mv.visitInsn( Opcodes.IRETURN );

            // if ( obj == null ) return false;
            mv.visitLabel( l1 );
            mv.visitVarInsn( Opcodes.ALOAD,
                    1 );
            Label l2 = new Label();
            mv.visitJumpInsn( Opcodes.IFNONNULL,
                    l2 );
            mv.visitInsn( Opcodes.ICONST_0 );
            mv.visitInsn( Opcodes.IRETURN );

            // if ( getClass() != obj.getClass() ) return false;
            mv.visitLabel( l2 );
            mv.visitVarInsn( Opcodes.ALOAD,
                    0 );
            mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName( Object.class ),
                    "getClass",
                    Type.getMethodDescriptor( Type.getType( Class.class ),
                            new Type[]{} ) );
            mv.visitVarInsn( Opcodes.ALOAD,
                    1 );
            mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName( Object.class ),
                    "getClass",
                    Type.getMethodDescriptor( Type.getType( Class.class ),
                            new Type[]{} ) );
            Label l3 = new Label();
            mv.visitJumpInsn( Opcodes.IF_ACMPEQ,
                    l3 );
            mv.visitInsn( Opcodes.ICONST_0 );
            mv.visitInsn( Opcodes.IRETURN );

            // final <classname> other = (<classname>) obj;
            mv.visitLabel( l3 );
            mv.visitVarInsn( Opcodes.ALOAD,
                    1 );
            mv.visitTypeInsn( Opcodes.CHECKCAST,
                    BuildUtils.getInternalType( classDef.getClassName() ) );
            mv.visitVarInsn( Opcodes.ASTORE,
                    2 );

            // for each key field
            int count = 0;
            for ( FieldDefinition field : classDef.getFieldsDefinitions() ) {
                if ( field.isKey() ) {
                    count++;

                    Label goNext = new Label();

                    if ( BuildUtils.isPrimitive(field.getTypeName()) ) {
                        // if attr is primitive

                        // if ( this.<attr> != other.<booleanAttr> ) return false;
                        mv.visitVarInsn( Opcodes.ALOAD,
                                0 );


                        visitFieldOrGetter(mv, classDef, field);

                        mv.visitVarInsn(Opcodes.ALOAD,
                                2);

                        visitFieldOrGetter(mv, classDef, field);

                        if ( field.getTypeName().equals( "long" ) ) {
                            mv.visitInsn( Opcodes.LCMP );
                            mv.visitJumpInsn( Opcodes.IFEQ,
                                    goNext );
                        } else if ( field.getTypeName().equals( "double" ) ) {
                            mv.visitInsn( Opcodes.DCMPL );
                            mv.visitJumpInsn( Opcodes.IFEQ,
                                    goNext );
                        } else if ( field.getTypeName().equals( "float" ) ) {
                            mv.visitInsn( Opcodes.FCMPL );
                            mv.visitJumpInsn( Opcodes.IFEQ,
                                    goNext );
                        } else {
                            // boolean, byte, char, short, int
                            mv.visitJumpInsn( Opcodes.IF_ICMPEQ,
                                    goNext );
                        }
                        mv.visitInsn( Opcodes.ICONST_0 );
                        mv.visitInsn( Opcodes.IRETURN );
                    } else {
                        // if attr is not a primitive

                        // if ( this.<attr> == null && other.<attr> != null ||
                        //      this.<attr> != null && ! this.<attr>.equals( other.<attr> ) ) return false;
                        mv.visitVarInsn( Opcodes.ALOAD,
                                0 );

                        visitFieldOrGetter(mv, classDef, field);

                        Label secondIfPart = new Label();
                        mv.visitJumpInsn( Opcodes.IFNONNULL,
                                secondIfPart );

                        // if ( other.objAttr != null ) return false;
                        mv.visitVarInsn( Opcodes.ALOAD,
                                2 );

                        visitFieldOrGetter(mv, classDef, field);

                        Label returnFalse = new Label();
                        mv.visitJumpInsn( Opcodes.IFNONNULL,
                                returnFalse );

                        mv.visitLabel( secondIfPart );
                        mv.visitVarInsn( Opcodes.ALOAD,
                                0 );

                        visitFieldOrGetter(mv, classDef, field);

                        mv.visitJumpInsn( Opcodes.IFNULL,
                                goNext );

                        mv.visitVarInsn( Opcodes.ALOAD,
                                0 );

                        visitFieldOrGetter(mv, classDef, field);

                        mv.visitVarInsn( Opcodes.ALOAD,
                                2 );

                        visitFieldOrGetter(mv, classDef, field);

                        if ( ! BuildUtils.isArray( field.getTypeName() ) ) {
                            mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL,
                                    "java/lang/Object",
                                    "equals",
                                    "(Ljava/lang/Object;)Z" );
                        } else {
                            mv.visitMethodInsn( Opcodes.INVOKESTATIC,
                                    "java/util/Arrays",
                                    "equals",
                                    "(" +
                                            BuildUtils.arrayType( field.getTypeName() ) +
                                            BuildUtils.arrayType( field.getTypeName() ) +
                                            ")Z" );
                        }
                        mv.visitJumpInsn( Opcodes.IFNE,
                                goNext );

                        mv.visitLabel( returnFalse );
                        mv.visitInsn( Opcodes.ICONST_0 );
                        mv.visitInsn( Opcodes.IRETURN );
                    }
                    mv.visitLabel( goNext );
                }
            }
            if ( count > 0 ) {
                mv.visitInsn( Opcodes.ICONST_1 );
            } else {
                mv.visitInsn( Opcodes.ICONST_0 );
            }
            mv.visitInsn( Opcodes.IRETURN );
            Label lastLabel = null;
            if ( this.debug ) {
                lastLabel = new Label();
                mv.visitLabel( lastLabel );
                mv.visitLocalVariable( "this",
                        BuildUtils.getTypeDescriptor( classDef.getClassName() ),
                        null,
                        l0,
                        lastLabel,
                        0 );
                mv.visitLocalVariable( "obj",
                        Type.getDescriptor( Object.class ),
                        null,
                        l0,
                        lastLabel,
                        1 );
                mv.visitLocalVariable( "other",
                        BuildUtils.getTypeDescriptor( classDef.getClassName() ),
                        null,
                        l0,
                        lastLabel,
                        2 );
            }
            mv.visitMaxs( 0,
                    0 );
            mv.visitEnd();
        }
    }


    protected void buildSystemHashCode(ClassWriter cw) {
        {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "hashCode", "()I", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "identityHashCode", "(Ljava/lang/Object;)I");
            mv.visitInsn(IRETURN);
            mv.visitMaxs( 0, 0 );
            mv.visitEnd();
        }
    }


    protected void buildHashCode(ClassVisitor cw,
                                 ClassDefinition classDef) {

        MethodVisitor mv;
        // Building hashCode() method
        {
            mv = cw.visitMethod( Opcodes.ACC_PUBLIC,
                    "hashCode",
                    "()I",
                    null,
                    null );
            mv.visitCode();
            Label l0 = null;
            if ( this.debug ) {
                l0 = new Label();
                mv.visitLabel( l0 );
            }

            // int result = 1;
            mv.visitInsn( Opcodes.ICONST_1 );
            mv.visitVarInsn( Opcodes.ISTORE,
                    1 );

            // for each key field
            for ( FieldDefinition field : classDef.getFieldsDefinitions() ) {
                if ( field.isKey() ) {

                    // result = result * 31 + <attr_hash>
                    mv.visitVarInsn( Opcodes.ILOAD,
                            1 );
                    mv.visitIntInsn( Opcodes.BIPUSH,
                            31 );
                    mv.visitVarInsn( Opcodes.ILOAD,
                            1 );
                    mv.visitInsn( Opcodes.IMUL );

                    mv.visitVarInsn(Opcodes.ALOAD,
                            0);

                    visitFieldOrGetter(mv, classDef, field);

                    if ( "boolean".equals( field.getTypeName() ) ) {
                        // attr_hash ::== <boolean_attr> ? 1231 : 1237;
                        Label blabel1 = new Label();
                        mv.visitJumpInsn( Opcodes.IFEQ,
                                blabel1 );
                        mv.visitIntInsn( Opcodes.SIPUSH,
                                1231 );
                        Label blabel2 = new Label();
                        mv.visitJumpInsn( Opcodes.GOTO,
                                blabel2 );
                        mv.visitLabel( blabel1 );
                        mv.visitIntInsn( Opcodes.SIPUSH,
                                1237 );
                        mv.visitLabel( blabel2 );
                    } else if ( "long".equals( field.getTypeName() ) ) {
                        // attr_hash ::== (int) (longAttr ^ (longAttr >>> 32))
                        mv.visitVarInsn( Opcodes.ALOAD,
                                0 );

                        visitFieldOrGetter(mv, classDef, field);

                        mv.visitIntInsn( Opcodes.BIPUSH,
                                32 );
                        mv.visitInsn( Opcodes.LUSHR );
                        mv.visitInsn( Opcodes.LXOR );
                        mv.visitInsn( Opcodes.L2I );

                    } else if ( "float".equals( field.getTypeName() ) ) {
                        // attr_hash ::== Float.floatToIntBits( floatAttr );
                        mv.visitMethodInsn( Opcodes.INVOKESTATIC,
                                Type.getInternalName( Float.class ),
                                "floatToIntBits",
                                "(F)I" );
                    } else if ( "double".equals( field.getTypeName() ) ) {
                        // attr_hash ::== (int) (Double.doubleToLongBits( doubleAttr ) ^ (Double.doubleToLongBits( doubleAttr ) >>> 32));
                        mv.visitMethodInsn( Opcodes.INVOKESTATIC,
                                Type.getInternalName( Double.class ),
                                "doubleToLongBits",
                                "(D)J" );
                        mv.visitInsn( Opcodes.DUP2 );
                        mv.visitIntInsn( Opcodes.BIPUSH,
                                32 );
                        mv.visitInsn( Opcodes.LUSHR );
                        mv.visitInsn( Opcodes.LXOR );
                        mv.visitInsn( Opcodes.L2I );
                    } else if ( !BuildUtils.isPrimitive(field.getTypeName()) ) {
                        // attr_hash ::== ((objAttr == null) ? 0 : objAttr.hashCode());
                        Label olabel1 = new Label();
                        mv.visitJumpInsn( Opcodes.IFNONNULL,
                                olabel1 );
                        mv.visitInsn( Opcodes.ICONST_0 );
                        Label olabel2 = new Label();
                        mv.visitJumpInsn( Opcodes.GOTO,
                                olabel2 );
                        mv.visitLabel( olabel1 );
                        mv.visitVarInsn( Opcodes.ALOAD,
                                0 );

                        visitFieldOrGetter(mv, classDef, field);

                        if ( ! BuildUtils.isArray( field.getTypeName() ) ) {
                            mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL,
                                    "java/lang/Object",
                                    "hashCode",
                                    "()I" );
                        } else {
                            mv.visitMethodInsn( INVOKESTATIC,
                                    "java/util/Arrays",
                                    "hashCode",
                                    "(" + BuildUtils.arrayType( field.getTypeName() ) + ")I");
                        }
                        mv.visitLabel( olabel2 );
                    }

                    mv.visitInsn( Opcodes.IADD );
                    mv.visitVarInsn( Opcodes.ISTORE,
                            1 );
                }
            }
            mv.visitVarInsn( Opcodes.ILOAD,
                    1 );
            mv.visitInsn( Opcodes.IRETURN );

            Label lastLabel = null;
            if ( this.debug ) {
                lastLabel = new Label();
                mv.visitLabel( lastLabel );
                mv.visitLocalVariable( "this",
                        BuildUtils.getTypeDescriptor( classDef.getClassName() ),
                        null,
                        l0,
                        lastLabel,
                        0 );
                mv.visitLocalVariable( "hash",
                        Type.getDescriptor( int.class ),
                        null,
                        l0,
                        lastLabel,
                        1 );
            }
            mv.visitMaxs( 0,
                    0 );
            mv.visitEnd();
        }
    }

    protected void buildToString(ClassVisitor cw,
                                 ClassDefinition classDef) {
        MethodVisitor mv;
        {
            mv = cw.visitMethod( Opcodes.ACC_PUBLIC,
                                 "toString",
                                 "()Ljava/lang/String;",
                                 null,
                                 null );
            mv.visitCode();

            Label l0 = null;
            if ( this.debug ) {
                l0 = new Label();
                mv.visitLabel( l0 );
            }

            // StringBuilder buf = new StringBuilder();
            mv.visitTypeInsn( Opcodes.NEW,
                              Type.getInternalName( StringBuilder.class ) );
            mv.visitInsn( Opcodes.DUP );
            mv.visitMethodInsn( Opcodes.INVOKESPECIAL,
                                Type.getInternalName( StringBuilder.class ),
                                "<init>",
                                "()V" );
            mv.visitVarInsn( Opcodes.ASTORE,
                             1 );

            // buf.append(this.getClass().getSimpleName())
            mv.visitVarInsn( Opcodes.ALOAD,
                             1 );
            mv.visitVarInsn( Opcodes.ALOAD,
                             0 );
            mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL,
                                BuildUtils.getInternalType( classDef.getClassName() ),
                                "getClass",
                                "()Ljava/lang/Class;" );
            mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL,
                                Type.getInternalName( Class.class ),
                                "getSimpleName",
                                "()Ljava/lang/String;" );
            mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL,
                                Type.getInternalName( StringBuilder.class ),
                                "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;" );

            appendToStringBuilder(mv, "( ");
            buildFieldsToString( classDef, mv, false );
            appendToStringBuilder(mv, " )");

            mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL,
                                Type.getInternalName( StringBuilder.class ),
                                "toString",
                                "()Ljava/lang/String;" );
            mv.visitInsn( Opcodes.ARETURN );

            Label lastLabel = null;
            if ( this.debug ) {
                lastLabel = new Label();
                mv.visitLabel( lastLabel );
                mv.visitLocalVariable( "this",
                                       BuildUtils.getTypeDescriptor( classDef.getClassName() ),
                                       null,
                                       l0,
                                       lastLabel,
                                       0 );
                mv.visitLocalVariable( "buf",
                                       Type.getDescriptor( StringBuilder.class ),
                                       null,
                                       l0,
                                       lastLabel,
                                       1 );
            }
            mv.visitMaxs( 0, 0 );
            mv.visitEnd();
        }
    }

    protected boolean buildFieldsToString( ClassDefinition classDef, MethodVisitor mv, boolean previous ) {
        boolean first = true;
        for ( FieldDefinition field : classDef.getFieldsDefinitions() ) {
            buildFieldToString( field, classDef, mv, first );
            first = false;
        }
        return previous;
    }

    protected void buildFieldToString(FieldDefinition field, ClassDefinition classDef, MethodVisitor mv, boolean first) {
        if ( !first ) {
            // buf.append(", ");
            appendToStringBuilder(mv, ", ");
        }

        // buf.append(attrName)
        appendToStringBuilder(mv, field.getName());

        // buf.append("=");
        appendToStringBuilder(mv, "=");

        // buf.append(attrValue)
        if (field.isRecursive()) {
            appendToStringBuilder(mv, field.getTypeName() + " [recursive]");
        } else {
            mv.visitVarInsn(Opcodes.ALOAD,
                    0);

            visitFieldOrGetter( mv, classDef, field );

            if ( BuildUtils.isPrimitive(field.getTypeName()) ) {
                String type = field.getTypeName().matches( "(byte|short)" ) ? "int" : field.getTypeName();
                mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName( StringBuilder.class ),
                        "append",
                        Type.getMethodDescriptor( Type.getType( StringBuilder.class ),
                                new Type[]{Type.getType( BuildUtils.getTypeDescriptor( type ) )} ) );
            } else if ( BuildUtils.isArray( field.getTypeName() ) && BuildUtils.arrayDimSize( field.getTypeName() ) == 1 ) {


                mv.visitMethodInsn( INVOKESTATIC,
                        "java/util/Arrays",
                        "toString",
                        "(" + BuildUtils.getTypeDescriptor( BuildUtils.arrayType( field.getTypeName() ) ) + ")Ljava/lang/String;" );

                mv.visitMethodInsn( INVOKEVIRTUAL,
                        "java/lang/StringBuilder",
                        "append",
                        "(Ljava/lang/Object;)Ljava/lang/StringBuilder;" );

            } else {
                mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName( StringBuilder.class ),
                        "append",
                        Type.getMethodDescriptor( Type.getType( StringBuilder.class ),
                                new Type[]{Type.getType( Object.class )} ) );
            }
        }
    }

    private void appendToStringBuilder(MethodVisitor mv, String s) {
        mv.visitLdcInsn( s );
        mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL,
                            Type.getInternalName(StringBuilder.class),
                            "append",
                            "(Ljava/lang/String;)Ljava/lang/StringBuilder;" );
    }


    protected void buildClassAnnotations(ClassDefinition classDef, ClassVisitor cw) {
        if (classDef.getAnnotations() != null) {
            for (AnnotationDefinition ad : classDef.getAnnotations()) {
                AnnotationVisitor av = cw.visitAnnotation("L"+BuildUtils.getInternalType(ad.getName())+";", true);
                for (String key : ad.getValues().keySet()) {
                    AnnotationDefinition.AnnotationPropertyVal apv = ad.getValues().get(key);

                    switch (apv.getValType()) {
                        case STRINGARRAY:
                            AnnotationVisitor subAv = av.visitArray(apv.getProperty());
                            Object[] array = (Object[]) apv.getValue();
                            for (Object o : array) {
                                subAv.visit(null,o);
                            }
                            subAv.visitEnd();
                            break;
                        case PRIMARRAY:
                            av.visit(apv.getProperty(),apv.getValue());
                            break;
                        case ENUMARRAY:
                            AnnotationVisitor subEnav = av.visitArray(apv.getProperty());
                            Enum[] enArray = (Enum[]) apv.getValue();
                            String aenumType = "L" + BuildUtils.getInternalType(enArray[0].getClass().getName()) + ";";
                            for (Enum enumer : enArray) {
                                subEnav.visitEnum(null,aenumType,enumer.name());
                            }
                            subEnav.visitEnd();
                            break;
                        case CLASSARRAY:
                            AnnotationVisitor subKlav = av.visitArray(apv.getProperty());
                            Class[] klarray = (Class[]) apv.getValue();
                            for (Class klass : klarray) {
                                subKlav.visit(null,Type.getType("L"+BuildUtils.getInternalType(klass.getName())+";"));
                            }
                            subKlav.visitEnd();
                            break;
                        case ENUMERATION:
                            String enumType = "L" + BuildUtils.getInternalType(apv.getType().getName()) + ";";
                            av.visitEnum(apv.getProperty(),enumType,((Enum) apv.getValue()).name());
                            break;
                        case KLASS:
                            String klassName = BuildUtils.getInternalType(((Class) apv.getValue()).getName());
                            av.visit(apv.getProperty(),Type.getType("L"+klassName+";"));
                            break;
                        case PRIMITIVE:
                            av.visit(apv.getProperty(),apv.getValue());
                            break;
                        case STRING:
                            av.visit(apv.getProperty(),apv.getValue());
                            break;
                    }

                }
                av.visitEnd();
            }
        }
    }




    protected void buildFieldAnnotations(FieldDefinition fieldDef, FieldVisitor fv) {
        if (fieldDef.getAnnotations() != null) {
            for (AnnotationDefinition ad : fieldDef.getAnnotations()) {
                AnnotationVisitor av = fv.visitAnnotation("L"+BuildUtils.getInternalType(ad.getName())+";", true);
                for (String key : ad.getValues().keySet()) {
                    AnnotationDefinition.AnnotationPropertyVal apv = ad.getValues().get(key);

                    switch (apv.getValType()) {
                        case STRINGARRAY:
                            AnnotationVisitor subAv = av.visitArray(apv.getProperty());
                            Object[] array = (Object[]) apv.getValue();
                            for (Object o : array) {
                                subAv.visit(null,o);
                            }
                            subAv.visitEnd();
                            break;
                        case PRIMARRAY:
                            av.visit(apv.getProperty(),apv.getValue());
                            break;
                        case ENUMARRAY:
                            AnnotationVisitor subEnav = av.visitArray(apv.getProperty());
                            Enum[] enArray = (Enum[]) apv.getValue();
                            String aenumType = "L" + BuildUtils.getInternalType(enArray[0].getClass().getName()) + ";";
                            for (Enum enumer : enArray) {
                                subEnav.visitEnum(null,aenumType,enumer.name());
                            }
                            subEnav.visitEnd();
                            break;
                        case CLASSARRAY:
                            AnnotationVisitor subKlav = av.visitArray(apv.getProperty());
                            Class[] klarray = (Class[]) apv.getValue();
                            for (Class klass : klarray) {
                                subKlav.visit(null,Type.getType("L"+BuildUtils.getInternalType(klass.getName())+";"));
                            }
                            subKlav.visitEnd();
                            break;
                        case ENUMERATION:
                            String enumType = "L" + BuildUtils.getInternalType(apv.getType().getName()) + ";";
                            av.visitEnum(apv.getProperty(),enumType,((Enum) apv.getValue()).name());
                            break;
                        case KLASS:
                            String klassName = BuildUtils.getInternalType(((Class) apv.getValue()).getName());
                            av.visit(apv.getProperty(),Type.getType("L"+klassName+";"));
                            break;
                        case PRIMITIVE:
                            av.visit(apv.getProperty(),apv.getValue());
                            break;
                        case STRING:
                            av.visit(apv.getProperty(),apv.getValue());
                            break;
                    }
                }
                av.visitEnd();
            }
        }
    }





    protected void visitFieldOrGetter(MethodVisitor mv, ClassDefinition classDef, FieldDefinition field) {
        if (! field.isInherited()) {
            mv.visitFieldInsn( Opcodes.GETFIELD,
                    BuildUtils.getInternalType( classDef.getClassName() ),
                    field.getName(),
                    BuildUtils.getTypeDescriptor( field.getTypeName() ) );
        } else {
            mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL,
                    BuildUtils.getInternalType( classDef.getClassName()),
                    field.getReadMethod(),
                    Type.getMethodDescriptor(Type.getType(BuildUtils.getTypeDescriptor(field.getTypeName())),
                            new Type[]{})
            );
        }
    }

}

