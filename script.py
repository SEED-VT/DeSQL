import sys
import pandas as pd
import matplotlib.pyplot as plt

def read_data(file_path):
    # Read the data from the file and convert it to a list of floats
    with open(file_path, 'r') as file:
        data = file.read().strip().split(',')
        data = [float(num) for num in data if num.strip()]
    
    # Process data in chunks of 2 to compute the average
    averages = []
    for i in range(0, len(data), 2):
        chunk = data[i:i+2]
        if chunk:  # Check if chunk is not empty
            average = sum(chunk) / len(chunk)
            averages.append(average)
    return averages

def plot_data(data1, data2):
    # Create a DataFrame from the data
    df = pd.DataFrame({
        'Desql Average': data1,
        'Vanilla Spark Average': data2
    })
    
    # Plotting the data
    plt.figure(figsize=(10, 5))
    plt.plot(df['Desql Average'], label='Desql', marker='o')
    plt.plot(df['Vanilla Spark Average'], label='Vanilla Spark', marker='x')
    
    # Adding title and labels
    plt.title('Comparison of Desql Overhead with Vanilla Spark')
    plt.xlabel('Query #')
    plt.ylabel('Average Time (s)')
    plt.legend()
    
    # Show the plot
    plt.grid(True)
    plt.show()

if __name__ == "__main__":
    # Check if enough arguments have been passed
    if len(sys.argv) < 3:
        print("Usage: python script.py <datafile1> <datafile2>")
        sys.exit(1)

    # Paths to the data files from command line arguments
    data1_path = sys.argv[1]
    data2_path = sys.argv[2]

    # Read the data
    data1 = read_data(data1_path)
    data2 = read_data(data2_path)

    # Plot the data
    plot_data(data1, data2)
