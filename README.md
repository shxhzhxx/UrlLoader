# UrlLoader
Android Http Download Library

https://github.com/shxhzhxx/ImageLoader



## Dependency


**Step 1.** Add the JitPack repository to your build file 

Add it in your root build.gradle at the end of repositories:

```
	allprojects {
		repositories {
			...
			maven { url 'https://jitpack.io' }
		}
	}
```

**Step 2.** Add the dependency<br>

[![](https://www.jitpack.io/v/shxhzhxx/UrlLoader.svg)](https://www.jitpack.io/#shxhzhxx/UrlLoader)
```
	dependencies {
	        implementation 'com.github.shxhzhxx:UrlLoader:2.1.8'
	}
```



## Features

- Simple API
- Partial download base on Http protocol
- Max disk cache control
- Combine identical download to save bandwidth



## Usage


```kotlin
val loader = UrlLoader(cacheDir)
val url = "http://xxx"
val id = loader.load(url, onLoad = { file ->
	//success
}, onProgress = { total, current, speed ->

}, onCancel = {

}, onFailure = {

})
```
<br><br><br>
## Description
用于Android的http文件下载代理，主要分3个核心模块：

- 文件缓存管理模块（FileLruCache）<br>
    基于Android的FileObserver监听目录下文件修改事件。<br>
    支持设置最大缓存容量，超出容量时，会自动清除部分文件以满足最大容量限制。<br>
    利用File类的lastModified属性记录文件最后访问时间，实现LRU效果。<br>

    *开发难点：*<br>
    初始化时会读取目录下所有文件，按照文件的lastModified排序；与此同时，其他线程甚至进程可能修改该目录下的文件。需要妥善处理好初始化时的并发问题。<br>
    <br>
    *理想的效果：*<br>
    1、在模块启动时，将目录当前存在的文件列表读入内存，并按照lastModified从大到小排序（最大值在列表头部，最小值在尾部）；如果目录下文件总长度超出最大容量限制，从列表尾部开始删除，直至总长度小于等于最大容量。上述操作应该是原子的，没有其他进程和线程可以在过程中读/改目录下的文件。<br>
    2、模块启动完毕后，有任何文件操作（包括读）时，更新该文件的lastModified属性为系统当前时间，并将其移至列表头部；如果目录下文件总长度超出最大容量限制，从列表尾部开始删除，直至总长度小于等于最大容量。<br>
    <br>
    *折中方案：*<br>
    由于模块启动时的初始化操作无法保证原子性。按如下逻辑顺序启动模块：<br>
    - 启动FileObserver监听目标目录，并将收到的文件事件排入一个阻塞队列（BlockQueue）。
    - 获取目录下的文件列表。
    - 读取每个文件的lastModified、length并将其存入内存。
    - 根据lastModified从大到小排序文件列表。
    - 按序处理队列中的所有事件（使用超时为100ms的阻塞出队，超过100ms没有事件出队就看作队列已被清空），将事件对应的文件的lastModified属性为系统当前时间，更新length，并将其移至列表头部（如果文件之前不存在于列表中，则在列表头部新增一个元素）。
    - 设置LruCache的最大缓存容量，按序从文件列表的尾部开始读取文件，并插入LruCache中，超出最大缓存时，较旧的文件会被清除。（假定这一步是原子的）
    - 启动完毕。

    *证明折中方案：*<br>
    所有文件分为两类，从开始监听到启动完毕没有被修改的文件归为集合A，其他的归为集合B。<br>
    - 在启动完毕时，文件列表的顺序正确。<br>
    对于集合A，第三步读取的lastModified有效，第四步排序的结果为正确的相对顺序（相对A集合其他文件）。<br>
    对于集合B，所有文件都至少有一个相对应的事件等待处理。由于每次处理事件都会将对应文件排到列表头部，所以每个文件的最后一个事件决定了该文件在LRU队列中的位置，同时由于事件按序传递，最后一个事件也代表了文件的实际顺序。<br>
    同样由于每次处理事件都会将对应文件排到列表头部，集合B的所有文件都排在集合A的文件前面。
    - 在启动完毕时，内存中记录的文件length正确。
    对于集合A，第三步读取的length正确。
    对于集合B，文件发生的每次读写操作都会产生事件，第五步处理事件时会更新length，所以处理完所有事件后，length一定正确。
    - 所有文件顺序正确，length正确的情况下，模块可以正确启动。
    

    *举一个例子来说明理想的效果与折中方案的差别：*<br>
    用(name,lastModified,length)三元组来表示文件。<br>
    初始化时，目录下有(A,1,3)、(B,2,5)、(C,3,4)三个文件，最大缓存容量为10，用户初始化文件管理模块后，立刻尝试读取A文件的内容。<br>
    理想情况下，A文件应该在初始化方法调用时已经被清除，所以用户读取不到A的内容。<br>
    折中方案下，用户读取A时，很可能A文件还没有被清除（比如模块此时正在排序读入内存的文件列表）。这时用户可以读取A文件内的内容，且会产生一个文件读事件，导致初始化时会将A排在列表头部，最后的结果是B文件被清除，AC被保留。
    <br>
    

    *对第六步假定的说明：*<br>
    上面假定了初始化的第六步是原子的，这个假定当然是不成立的。那么会有什么影响呢？由于所有文件的lastModified、length都已读入内存，所以相当于保存了一个当前时间点目录状态的快照，后续的插入操作虽然不原子，但却是严格按照这个时间点状态来执行的。可能产生的影响是，在生成快照的时间点，某个文件逻辑上应被清除，在模块删除这个文件前，其他进程依然可以访问这个文件，但这个文件终究是要被删除的，因为新的操作并没有被记录在快照之中。初始化完毕后，虽然会收到这次文件读写事件，但文件已经被删除（length=0），对缓存结果不会产生影响。

    *证明第六步假定的影响：*<br>
    将第五步生成的文件列表（快照）内的文件分为两类，逻辑上应该被清除的归为集合A，其他的归为集合B。<br>
    对于集合B的文件，如果第六步执行过程中某些文件发生修改，模块初始化完毕后会处理这些事件，从结果来看，与第六步原子的执行效果一致。<br>
    对于集合A的文件，第六步执行完毕后会将其全部删除，所以过程中如果某些文件发生修改，这些修改结果会丢失。模块初始化完毕后，会收到这些事件，由于文件已经被删除，对缓存结果不会产生影响。


    局限以及潜在问题：<br>
    1、初始化时，等待event的超时时100ms。由于FileObserver的event回调在一个单线程里执行，如果进程的其他地方阻塞了这个线程，可能导致初始化时没有处理完所有的event。<br>
    2、event对应的文件用路径标识，但是路径可能被修改、删除甚至重新创建一个相同路径的新文件，这时旧文件如果依然处于被打开的状态（例如持有一个输入流），FileObserver就会继续收到旧文件的事件，但根据事件的路径找到的文件却是另外一个文件。FileObserver的底层实现inotify也有同样的问题，具体请参考[man pages](http://man7.org/linux/man-pages/man7/inotify.7.html)的NOTES->Limitations and caveats<br>
    3、FileObserver基于linux的inotify，其事件队列长度有上限，超出后事件会被丢弃。

<br><br><br>
- 任务管理模块（TaskManager）<br>
    TaskManager是一个抽象类，负责调度耗时任务，继承它只需要编写任务的具体执行代码。<br>
    *TaskManager提供了以下功能：*<br>
    - 任务启动支持同步异步两种方式。异步方法需要传入一个监听者（任务完成后监听者会收到结果回调），并会返回一个监听者id；同步方法不需要传入监听者，阻塞直到任务结束，直接返回任务结果。
    - 启动任务时指定一个key。如果存在key相同的任务正在执行，则不启动新任务，而是等待之前的任务结束并直接获得返回结果（通过监听者或者阻塞后直接返回）。
    - 同步异步两种方式互相之间可以共享任务。即，同步方式创建了一个任务后，异步启动了同样key的任务时，不会创建新任务，在同步任务完成后，结果会传递直接给异步监听者；异步方式创建一个任务后，同步启动了同样key的任务时，不会创建新任务，而是阻塞当前线程等待异步任务完成，然后返回结果。
    - 任务可以被取消。<br>
    可以直接取消某个key对应的任务，此时这个任务所有的异步监听者都会收到任务取消的回调，同步方法都会返回null；<br>
    可以通过监听者id取消异步监听，如果任务当前还有其他监听者或同步方法在等待其执行结果，任务会继续执行，否则任务取消执行；<br>
    同步方法也可以取消（通过interrupt当前线程的方式），如果任务是在同步方法的调用线程中执行，且有其他监听者或同步方法在等待结果，那么任务会被转交给其他（线程池或其他同步方法的）线程执行，该同步方法返回null。

    *待优化：*<br>
    任务在执行过程中，如何打包执行上下文，切换到另一个线程继续执行是我一直没有攻克的一个技术难题。这个问题与kotlin的协程挂起（suspend）机制类似，都是需要暂存内存中的执行状态、各种变量数据，恢复执行时需要将这些数据重新载入运行环境。具体机制还有待研究。


<br><br><br>
- HTTP下载模块
    基于okhttp实现的http协议下载代理，主要在okhttp的基础上实现了断点续传和任务聚合（TaskManager）。由于[okhttp不支持断点续传](https://square.github.io/okhttp/4.x/okhttp/okhttp3/-cache/#cache-optimization)，所以缓存（FileLruCache）相关的逻辑都需要自己实现，包括断点续传（处理206、200两种情况），缓存有效期的验证（包括本地验证和服务端304验证）。<br>
    为了降低代码复杂度，提升运行效率，没有对文件完整性进行校验。以文件内容不会被篡改为前提设计的代码逻辑，完全遵守http标准协议实现。
