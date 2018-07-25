# UrlLoader
Android Http Download Library



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

**Step 2.** Add the dependency

```
	dependencies {
	        implementation 'com.github.shxhzhxx:UrlLoader:1.1.0'
	}
```


## Features

- Simple API
- Partial download base on Http protocol
- Max disk cache control
- Combine identical download to save bandwidth



## Usage

#### Use it directly

```java
UrlLoader urlLoader=new UrlLoader(getCacheDir(),100*1024*1024,5);
String url="http://xxx";
urlLoader.load(url, new UrlLoader.ProgressObserver() {
	@Override
	public void onComplete(File file) {
		//success
	}
});
```



#### Use singleton pattern 

DownloadManager.java

```java
public abstract class DownloadManager {
    private static UrlLoader mInstance;

    public static void init(@NonNull Context context) {
        mInstance = new UrlLoader(context.getCacheDir(), 300 * 1024 * 1024, 5);
    }

    public static UrlLoader getInstance() {
        return mInstance;
    }
}
```

Application.java

```java
@Override
public void onCreate() {
    super.onCreate();
    DownloadManager.init(this);
}
```

then you can use it anywhere

```java
DownloadManager.getInstance().load(url);
```

