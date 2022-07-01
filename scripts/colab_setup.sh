#!/bin/bash

#default values for pyspark, spark-nlp, and SPARK_HOME
SPARKNLP="4.0.1"
PYSPARK="3.2.1"

while getopts s:p:g option
do
 case "${option}"
 in
 s) SPARKNLP=${OPTARG};;
 p) PYSPARK=${OPTARG};;
 g) GPU="true";;
 esac
done

echo "setup Colab for PySpark $PYSPARK and Spark NLP $SPARKNLP"
export JAVA_HOME="/usr/lib/jvm/java-11-openjdk-amd64"

if [[ "$PYSPARK" == "3.2"* ]]; then
  PYSPARK="3.2.1"
  echo "Installing PySpark $PYSPARK and Spark NLP $SPARKNLP"
elif [[ "$PYSPARK" == "3.1"* ]]; then
  PYSPARK="3.1.3"
  echo "Installing PySpark $PYSPARK and Spark NLP $SPARKNLP"
elif [[ "$PYSPARK" == "3.0"* ]]; then
  PYSPARK="3.0.3"
  echo "Installing PySpark $PYSPARK and Spark NLP $SPARKNLP"
else
  PYSPARK="3.0.3"
  export JAVA_HOME="/usr/lib/jvm/java-11-openjdk-amd64"
fi

if [[ "$GPU" == "true" ]];
  then
    echo "Upgrading libcudnn8 to 8.1.0 for GPU"
    apt install -qq --allow-change-held-packages libcudnn8=8.1.0.77-1+cuda11.2 -y &> /dev/null
fi

# Install pyspark spark-nlp
! pip install --upgrade -q pyspark==$PYSPARK spark-nlp==$SPARKNLP findspark
