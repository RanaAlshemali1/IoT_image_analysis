package edu.uga.cs.ada;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.TextView;

public
class Display extends AppCompatActivity {

    @Override
    protected
    void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_display);
        TextView textView = (TextView)findViewById(R.id.textView);

        Intent intent = getIntent();
        String finalImageResult = intent.getStringExtra("finalImageResult");

        textView.setText(finalImageResult);

    }
}
