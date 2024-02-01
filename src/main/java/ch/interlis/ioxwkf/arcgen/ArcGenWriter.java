package ch.interlis.ioxwkf.arcgen;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

import com.vividsolutions.jts.geom.Coordinate;

import java.io.File;
import java.io.IOException;

import ch.ehi.basics.settings.Settings;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Viewable;
import ch.interlis.iom.IomObject;
import ch.interlis.iox.IoxEvent;
import ch.interlis.iox.IoxException;
import ch.interlis.iox.IoxFactoryCollection;
import ch.interlis.iox.IoxWriter;
import ch.interlis.iox_j.EndBasketEvent;
import ch.interlis.iox_j.EndTransferEvent;
import ch.interlis.iox_j.ObjectEvent;
import ch.interlis.iox_j.StartBasketEvent;
import ch.interlis.iox_j.StartTransferEvent;
import ch.interlis.iox_j.jts.Iox2jts;
import ch.interlis.iox_j.jts.Iox2jtsException;
import ch.interlis.ioxwkf.dbtools.AttributeDescriptor;

// TODO
// - Muss Encoding etwas bestimmtes sein? 
// - Muss/soll Delimiter wählbar sein? Wiederverwenden von CSV...
// - Mechanismus, falls keine ID (eindeutiger Wert) mitgeliefert wird. -> Weil die Reihenfolge der Attribute nicht garantiert ist (?), 
// mache ich immer eine eigene ID.
// - Es gibt Standardformat und Extended Format. Wir brauchen anscheinend das Extended. Am besten wäre es steuerbar über settings.
// - Noch keine Modellsupport.
// - Hardcodiert Uppercase-Attributnamen. Weiss nicht, ob notwendig für sonARMS
// - writeChars()-Methode in CsvWriter sehr elaboriert. Ich verzichte darauf. Es sind somit z.B. keine Carriage returns innerhalb eines 
// Wertes möglich.
// - Nur eine Geometrie wird unterstützt, wird nicht geprüft.

// "Spezifikation": siehe PDF in src/main/resources
public class ArcGenWriter implements IoxWriter {
    private BufferedWriter writer = null;
    private TransferDescription td = null;
    private String[] headerAttrNames = null;
    private boolean firstObj = true;
    private int nextId = 1;

    private static final String ID_ATTR_NAME = "ID";
    //private Character currentValueDelimiter = DEFAULT_VALUE_DELIMITER;
    private char currentValueSeparator = '\t';

    private String iliGeomAttrName = null;
    private String geometryType = null;
    private Integer coordDimension = 2;
    private List<AttributeDescriptor> attrDescs = null;
    
    private static final String COORD="COORD";
    private static final String MULTICOORD="MULTICOORD";
    private static final String POLYLINE="POLYLINE";
    private static final String MULTIPOLYLINE="MULTIPOLYLINE";
    private static final String MULTISURFACE="MULTISURFACE";
    
    public ArcGenWriter(File file) throws IoxException {
        this(file, null);
    }

    public ArcGenWriter(File file, Settings settings) throws IoxException {
        init(file, settings);
    }

    private void init(File file, Settings settings) throws IoxException {        
        try {
            // Ohne encoding-Support. Falls notwendig, siehe CsvWriter.
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file)));
        } catch (IOException e) {
            throw new IoxException("could not create file", e);
        }        
    }
    
    public void setModel(TransferDescription td) {
        if(headerAttrNames != null) {
            throw new IllegalStateException("attributes must not be set");
        }
        this.td = td;
    }

//    public void setAttributes(String [] attr) {
//        if(td != null) {
//            throw new IllegalStateException("interlis model must not be set");
//        }
//        headerAttrNames = attr.clone();
//    }
    
    // Das Geometrieattribut wird an erster Stelle platziert.
    // TODO: Nochmals über die Bücher, ob wirklich sinnvoll und
    // notwendig. Sonst einfache beim Header zweimal interieren.
    public void setAttributeDescriptors(AttributeDescriptor[] attrDescs) throws IoxException {
        this.attrDescs = new ArrayList<AttributeDescriptor>();
        for (AttributeDescriptor attrDesc : attrDescs) {
            if (attrDesc.getDbColumnGeomTypeName() != null) {
                if (iliGeomAttrName != null) {
                    throw new IoxException("only one geometry attribute allowed");
                }
                iliGeomAttrName = attrDesc.getIomAttributeName();
                geometryType = attrDesc.getDbColumnGeomTypeName();
                coordDimension = attrDesc.getCoordDimension();
            }
            this.attrDescs.add(attrDesc); 
        }
        if (iliGeomAttrName == null) {
            throw new IoxException("no geometry attribute found");
        } 
    } 
    
    @Override
    public void close() throws IoxException {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                throw new IoxException(e);
            }
            writer = null;
        }
    }

    @Override
    public void write(IoxEvent event) throws IoxException {
        if (event instanceof StartTransferEvent) {
        } else if (event instanceof StartBasketEvent) {
        } else if (event instanceof ObjectEvent) {
            ObjectEvent obj = (ObjectEvent) event;
            IomObject iomObj = (IomObject)obj.getIomObject();
            if(firstObj) {
                // get list of attr names
                if (td != null) {
                    // not supported
//                    Viewable resultViewableHeader=findViewable(iomObj);
//                    if(resultViewableHeader==null) {
//                        throw new IoxException("class "+iomObj.getobjecttag()+" in model not found");
//                    }
//                    headerAttrNames=getAttributeNames(resultViewableHeader);
                } else {
                    // Falls setAttributes() nicht von aussen verwendet.
                    // Aus erstem Objekt eruieren. 
                    // TODO
//                    if (headerAttrNames == null) {
//                        headerAttrNames = getAttributeNames(iomObj);
//                    }
                }
                try {
                    writeHeader(attrDescs);
                } catch (IOException e) {
                    throw new IoxException(e);
                }
                firstObj = false;
            }
            String[] validAttrValues = getAttributeValues(attrDescs, iomObj);
            try {
                writeRecord(validAttrValues);
            } catch (IOException e) {
                throw new IoxException(e);
            }
        } else if (event instanceof EndBasketEvent) {
        } else if (event instanceof EndTransferEvent) {
            try {
                writer.write("END");
            } catch (IOException e) {
               new IoxException(e.getMessage());
            }

            close();
        } else {
            throw new IoxException("unknown event type "+event.getClass().getName());
        }
    }
    
    // Hier müsste wieder Reihenfolge-Logik rein, wenn es nur String-Array ist
    // Braucht ggf auch Linebreak.
    private void writeRecord(String[] attrValues) throws IOException, IoxException {
        boolean first = true;
        
        writer.write(getNextId());
        writer.write(currentValueSeparator);

        for (String value : attrValues) {
            if (!first) {
                writer.write(currentValueSeparator);
            }
            //writeChars(value);
            writer.write(value);
            first = false;
        }
        writer.newLine();
    }

    private void writeHeader(List<AttributeDescriptor> attrDescs) throws IOException {
        boolean firstName = true;
        
        // Hardcodiertes ID-Attribut mit Maschinenwert. 
        writer.write(ID_ATTR_NAME);
        writer.write(currentValueSeparator);
        
        // first loop to find out geometry attribute
        for (AttributeDescriptor attrDesc : attrDescs) {
            // FIXME: isGeometry() wirft NullPointer. -> Ticket machen
            if (attrDesc.getDbColumnGeomTypeName() != null) {
                
                // TODO andere Geometrietypen
                
                writer.write("X");
                writer.write(currentValueSeparator);
                writer.write("Y");
                
                if (coordDimension == 3) {
                    writer.write(currentValueSeparator);
                    writer.write("Z");
                } 
                
                if (attrDescs.size() > 1) {
                    writer.write(currentValueSeparator);
                }
            }
        }
        
        // second loop for all other attributes
        for (AttributeDescriptor attrDesc : attrDescs) {
            if (attrDesc.getDbColumnGeomTypeName() != null) {
                continue;
            }
            if (!firstName) {
                writer.write(currentValueSeparator);
            }
            firstName = false;
            writer.write(attrDesc.getIomAttributeName().toUpperCase());
        }
        writer.newLine();
    }
    
    /*
     * Es werden nur die Attribute in die Datei geschrieben, die auch in attrNames
     * vorkommen. D.h. im IomObject können mehr Attribute vorhanden sein, als dann
     * tatsächlich exportiert werden.
     */
    
    // FIXME: Reihenfolge stimmt nun nicht, weil attrDescs nicht mehr sortiert.
    private String[] getAttributeValues(List<AttributeDescriptor> attrDescs, IomObject currentIomObject) throws IoxException {
        String[] attrValues = new String[attrDescs.size()];
        for (int i = 0; i < attrDescs.size(); i++) {
            System.out.println(attrDescs.get(i).getIomAttributeName());
            String attrValue;
            if (attrDescs.get(i).getIomAttributeName().equals(iliGeomAttrName)) {
                // TODO Hier muss des encoden passieren.
                //attrValue = "geometrie...";
                attrValue = encodeGeometry(currentIomObject);
            } else {
                attrValue = currentIomObject.getattrvalue(attrDescs.get(i).getIomAttributeName());     
                System.out.println("Sachattribut: " + attrValue);
            }
            attrValues[i] = attrValue;
        }
        return attrValues;
    }
    
    private String encodeGeometry(IomObject iomObj) throws IoxException {
        IomObject geomObj = iomObj.getattrobj(iliGeomAttrName, 0);

        String attrValue = null;
        if (geomObj != null) {
            try {
                if (geomObj.getobjecttag().equals(COORD)) {
                    Coordinate coord = Iox2jts.coord2JTS(geomObj);
                    attrValue = String.valueOf(coord.x) + currentValueSeparator + String.valueOf(coord.y);
                    
                    if (coordDimension == 3) {
                        attrValue += currentValueSeparator + String.valueOf(coord.z);
                    }
                } 
            }
            catch (Iox2jtsException e) {
                throw new IoxException(e);
            }
        }
        return attrValue;
    }

    private String getNextId() {
        int count = nextId;
        nextId += 1;
        return String.valueOf(count);
    }

    @Override
    public IomObject createIomObject(String arg0, String arg1) throws IoxException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void flush() throws IoxException {
        // TODO Auto-generated method stub

    }

    @Override
    public IoxFactoryCollection getFactory() throws IoxException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setFactory(IoxFactoryCollection arg0) throws IoxException {
        // TODO Auto-generated method stub

    }
}
